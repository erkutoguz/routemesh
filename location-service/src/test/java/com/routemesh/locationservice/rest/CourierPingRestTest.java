package com.routemesh.locationservice.rest;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.routemesh.locationservice.TestcontainersConfiguration;
import com.routemesh.locationservice.service.CourierLocationService;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

@SpringBootTest(properties = {
        "spring.grpc.server.port=0",
        "routemesh.tracking.consumer-enabled=false",
        "routemesh.tracking.telemetry-enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class CourierPingRestTest {

    private static final String PATH = "/api/v1/couriers/me/location";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CourierLocationService service;

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(1, 1, 41.0, 29.0)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void onlyCouriersMayPing() throws Exception {
        for (String role : new String[] {"MERCHANT", "WAREHOUSE", "OPS", "WORKFLOW"}) {
            ping(UUID.randomUUID().toString(), role, body(1, 1, 41.0, 29.0)).andExpect(status().isForbidden());
        }
    }

    @Test
    void courierPingIsAcceptedAndStaleIsDropped() throws Exception {
        String courier = UUID.randomUUID().toString();

        ping(courier, "COURIER", body(5, 2, 41.0, 29.0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        ping(courier, "COURIER", body(5, 2, 41.0, 29.0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));
        ping(courier, "COURIER", body(4, 99, 41.0, 29.0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));
        ping(courier, "COURIER", body(5, 3, 41.0, 29.0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        assertThat(service.findNearest(41.0, 29.0, 100, 10)).extracting("courierId").contains(courier);
    }

    @Test
    void invalidPingsAreBadRequests() throws Exception {
        String courier = UUID.randomUUID().toString();

        ping(courier, "COURIER", "{}").andExpect(status().isBadRequest());
        ping(courier, "COURIER", "nope").andExpect(status().isBadRequest());
        ping(courier, "COURIER", body(1, 1, 99.0, 29.0)).andExpect(status().isBadRequest());
        ping(courier, "COURIER", body(-1, 1, 41.0, 29.0)).andExpect(status().isBadRequest());
        ping("not-a-uuid", "COURIER", body(1, 1, 41.0, 29.0)).andExpect(status().isBadRequest());
    }

    private ResultActions ping(String subject, String role, String body) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", "Bearer " + TestJwts.token(subject, role))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(long epoch, long sequence, double lat, double lon) {
        return "{\"sessionEpoch\":%d,\"sequenceNumber\":%d,\"latitude\":%s,\"longitude\":%s}"
                .formatted(epoch, sequence, lat, lon);
    }
}
