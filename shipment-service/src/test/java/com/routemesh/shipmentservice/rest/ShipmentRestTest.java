package com.routemesh.shipmentservice.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;
import com.routemesh.shipmentservice.TestcontainersConfiguration;
import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.CreateShipmentCommand;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.service.ShipmentService;

@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class ShipmentRestTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ShipmentService service;

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/shipments/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void merchantSeesOnlyOwnShipments() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID id = create(merchant);

        getAs(id, merchant.toString(), "MERCHANT").andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(merchant.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        getAs(id, UUID.randomUUID().toString(), "MERCHANT").andExpect(status().isNotFound());
    }

    @Test
    void courierSeesOnlyAssignedShipments() throws Exception {
        UUID courier = UUID.randomUUID();
        UUID id = assigned(courier);

        getAs(id, courier.toString(), "COURIER").andExpect(status().isOk());
        getAs(id, UUID.randomUUID().toString(), "COURIER").andExpect(status().isNotFound());
        getAs(create(UUID.randomUUID()), courier.toString(), "COURIER").andExpect(status().isNotFound());
    }

    @Test
    void opsAndWarehouseSeeAnyShipment() throws Exception {
        UUID id = create(UUID.randomUUID());

        getAs(id, UUID.randomUUID().toString(), "OPS").andExpect(status().isOk());
        getAs(id, UUID.randomUUID().toString(), "WAREHOUSE").andExpect(status().isOk());
    }

    @Test
    void workflowOnlyTokenIsForbiddenOnRest() throws Exception {
        getAs(create(UUID.randomUUID()), "sa", "WORKFLOW").andExpect(status().isForbidden());
    }

    @Test
    void tokenWithTwoActorRolesIsForbidden() throws Exception {
        getAs(create(UUID.randomUUID()), UUID.randomUUID().toString(), "OPS", "MERCHANT")
                .andExpect(status().isForbidden());
    }

    @Test
    void actorIsDerivedFromRoleAndRecordedInHistory() throws Exception {
        UUID id = create(UUID.randomUUID());
        service.transition(new TransitionCommand(id, ShipmentStatus.PAID, Actor.SYSTEM, key(), null, null, null));

        transitionAs(id, "AT_WAREHOUSE", key(), UUID.randomUUID().toString(), "WAREHOUSE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AT_WAREHOUSE"));

        mvc.perform(get("/api/v1/ops/shipments/" + id + "/history").header("Authorization",
                        bearer(UUID.randomUUID().toString(), "OPS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].actor").value("WAREHOUSE"))
                .andExpect(jsonPath("$[2].fromStatus").value("PAID"))
                .andExpect(jsonPath("$[2].toStatus").value("AT_WAREHOUSE"));
    }

    @Test
    void actorFieldInBodyCannotElevateCaller() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID id = create(merchant);

        mvc.perform(post("/api/v1/shipments/" + id + "/transitions")
                        .header("Authorization", bearer(merchant.toString(), "MERCHANT"))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toStatus\":\"PAID\",\"actor\":\"SYSTEM\"}"))
                .andExpect(status().isConflict());
        assertThat(service.get(id).status()).isEqualTo(ShipmentStatus.PENDING_PAYMENT);
    }

    @Test
    void merchantCannotTransitionAnotherMerchantsShipment() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID id = create(owner);
        service.transition(new TransitionCommand(id, ShipmentStatus.PAID, Actor.SYSTEM, key(), null, null, null));

        transitionAs(id, "CANCELLED", key(), UUID.randomUUID().toString(), "MERCHANT")
                .andExpect(status().isNotFound());
        assertThat(service.get(id).status()).isEqualTo(ShipmentStatus.PAID);

        transitionAs(id, "CANCELLED", key(), owner.toString(), "MERCHANT").andExpect(status().isOk());
        assertThat(service.get(id).status()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void courierCanOnlyMoveOwnShipment() throws Exception {
        UUID courier = UUID.randomUUID();
        UUID id = assigned(courier);

        transitionAs(id, "OUT_FOR_DELIVERY", key(), UUID.randomUUID().toString(), "COURIER")
                .andExpect(status().isNotFound());
        transitionAs(id, "OUT_FOR_DELIVERY", key(), courier.toString(), "COURIER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"));
    }

    @Test
    void sameIdempotencyKeyIsReplayedAndMissingKeyIsRejected() throws Exception {
        UUID id = create(UUID.randomUUID());
        service.transition(new TransitionCommand(id, ShipmentStatus.PAID, Actor.SYSTEM, key(), null, null, null));
        String key = key();
        String ops = UUID.randomUUID().toString();

        transitionAs(id, "CANCELLED", key, ops, "OPS").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        transitionAs(id, "CANCELLED", key, ops, "OPS").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

        mvc.perform(post("/api/v1/shipments/" + id + "/transitions")
                        .header("Authorization", bearer(ops, "OPS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toStatus\":\"CANCELLED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidBodyIsBadRequestProblem() throws Exception {
        UUID id = create(UUID.randomUUID());

        mvc.perform(post("/api/v1/shipments/" + id + "/transitions")
                        .header("Authorization", bearer("ops", "OPS"))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        mvc.perform(post("/api/v1/shipments/" + id + "/transitions")
                        .header("Authorization", bearer("ops", "OPS"))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toStatus\":\"NOPE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void historyEndpointIsOpsOnly() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID id = create(merchant);
        String path = "/api/v1/ops/shipments/" + id + "/history";

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : new String[] {"MERCHANT", "COURIER", "WAREHOUSE", "WORKFLOW"}) {
            mvc.perform(get(path).header("Authorization", bearer(merchant.toString(), role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(path).header("Authorization", bearer("ops", "OPS"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ops/shipments/" + UUID.randomUUID() + "/history")
                        .header("Authorization", bearer("ops", "OPS")))
                .andExpect(status().isNotFound());
    }

    private ResultActions getAs(UUID id, String subject, String... roles) throws Exception {
        return mvc.perform(get("/api/v1/shipments/" + id).header("Authorization", bearer(subject, roles)));
    }

    private ResultActions transitionAs(UUID id, String to, String key, String subject, String role)
            throws Exception {
        return mvc.perform(post("/api/v1/shipments/" + id + "/transitions")
                .header("Authorization", bearer(subject, role))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"" + to + "\"}"));
    }

    private UUID create(UUID merchant) {
        return service.create(new CreateShipmentCommand(key(), merchant, "Ada Lovelace", "+905551112233",
                "Moda Cd. 12, Kadikoy", 40.9876, 29.0369, UUID.randomUUID(), 1001, 800, "TRY")).shipment().id();
    }

    private UUID assigned(UUID courier) {
        UUID id = create(UUID.randomUUID());
        service.transition(new TransitionCommand(id, ShipmentStatus.PAID, Actor.SYSTEM, key(), null, null, null));
        service.transition(new TransitionCommand(id, ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE, key(), null,
                null, null));
        service.transition(new TransitionCommand(id, ShipmentStatus.READY_FOR_DISPATCH, Actor.WAREHOUSE, key(),
                null, null, null));
        service.transition(new TransitionCommand(id, ShipmentStatus.ASSIGNED, Actor.SYSTEM, key(), null, courier,
                null));
        return id;
    }

    private static String bearer(String subject, String... roles) {
        return "Bearer " + TestJwts.token(subject, roles);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
