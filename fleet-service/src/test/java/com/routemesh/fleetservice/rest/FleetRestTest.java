package com.routemesh.fleetservice.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.routemesh.fleetservice.TestcontainersConfiguration;
import com.routemesh.fleetservice.domain.CourierStatus;
import com.routemesh.fleetservice.domain.CreateOfferCommand;
import com.routemesh.fleetservice.service.FleetService;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class FleetRestTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FleetService service;

    @Test
    void requestsWithoutTokenAreUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/fleet/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/fleet/me/status").accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IDLE\"}")).andExpect(status().is4xxClientError());
    }

    @Test
    void onlyCouriersMayUseTheFleetApi() throws Exception {
        String subject = UUID.randomUUID().toString();
        for (String role : new String[] {"MERCHANT", "WAREHOUSE", "OPS", "WORKFLOW"}) {
            mvc.perform(get("/api/v1/fleet/me").header("Authorization", bearer(subject, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/fleet/offers/" + UUID.randomUUID() + "/accept")
                    .header("Authorization", bearer(subject, role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void courierGoesIdleAndSeesItsProfile() throws Exception {
        String courier = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/fleet/me").header("Authorization", bearer(courier, "COURIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"));
        setStatus(courier, "IDLE").andExpect(status().isOk())
                .andExpect(jsonPath("$.courierId").value(courier))
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.maxWeightGrams").value(20000));
        setStatus(courier, "OFFLINE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFFLINE"));
    }

    @Test
    void invalidStatusRequestsAreRejected() throws Exception {
        String courier = UUID.randomUUID().toString();

        setStatus(courier, "BUSY").andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/fleet/me/status").header("Authorization", bearer(courier, "COURIER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/fleet/me").header("Authorization", bearer("not-a-uuid", "COURIER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void courierListsAndAcceptsItsOffer() throws Exception {
        UUID courier = UUID.randomUUID();
        service.setStatus(courier, CourierStatus.IDLE);
        UUID shipment = UUID.randomUUID();
        UUID offerId = service.createOffer(new CreateOfferCommand(UUID.randomUUID(), shipment, courier, 1200, 60))
                .offer().offerId();

        mvc.perform(get("/api/v1/fleet/me/offers").header("Authorization", bearer(courier.toString(), "COURIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offerId").value(offerId.toString()))
                .andExpect(jsonPath("$[0].shipmentId").value(shipment.toString()))
                .andExpect(jsonPath("$[0].status").value("OFFERED"));
        decide(offerId, "accept", courier.toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(get("/api/v1/fleet/me").header("Authorization", bearer(courier.toString(), "COURIER")))
                .andExpect(jsonPath("$.status").value("BUSY"))
                .andExpect(jsonPath("$.activeShipmentId").value(shipment.toString()));
        mvc.perform(get("/api/v1/fleet/me/offers").header("Authorization", bearer(courier.toString(), "COURIER")))
                .andExpect(jsonPath("$.length()").value(0));
        setStatus(courier.toString(), "OFFLINE").andExpect(status().isConflict());
    }

    @Test
    void courierCanRejectItsOffer() throws Exception {
        UUID courier = UUID.randomUUID();
        service.setStatus(courier, CourierStatus.IDLE);
        UUID offerId = service.createOffer(new CreateOfferCommand(UUID.randomUUID(), UUID.randomUUID(), courier,
                1200, 60)).offer().offerId();

        decide(offerId, "reject", courier.toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        decide(offerId, "accept", courier.toString()).andExpect(status().isConflict());
    }

    @Test
    void anotherCouriersOfferLooksNonexistent() throws Exception {
        UUID owner = UUID.randomUUID();
        service.setStatus(owner, CourierStatus.IDLE);
        UUID offerId = service.createOffer(new CreateOfferCommand(UUID.randomUUID(), UUID.randomUUID(), owner,
                1200, 60)).offer().offerId();

        decide(offerId, "accept", UUID.randomUUID().toString()).andExpect(status().isNotFound());
        decide(offerId, "reject", UUID.randomUUID().toString()).andExpect(status().isNotFound());
        decide(UUID.randomUUID(), "accept", owner.toString()).andExpect(status().isNotFound());
    }

    private ResultActions setStatus(String courier, String status) throws Exception {
        return mvc.perform(put("/api/v1/fleet/me/status").header("Authorization", bearer(courier, "COURIER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions decide(UUID offerId, String action, String courier) throws Exception {
        return mvc.perform(post("/api/v1/fleet/offers/" + offerId + "/" + action)
                .header("Authorization", bearer(courier, "COURIER")));
    }

    private static String bearer(String subject, String... roles) {
        return "Bearer " + TestJwts.token(subject, roles);
    }
}
