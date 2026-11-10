package com.routemesh.workflowservice.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;
import com.routemesh.workflowservice.workflow.FakeActivities;
import com.routemesh.workflowservice.workflow.ShipmentWorkflowImpl;

import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

@SpringBootTest(properties = { "routemesh.temporal.worker-enabled=false", "routemesh.dispatch.consumer-enabled=false" })
@AutoConfigureMockMvc
@Import({ TestJwtConfiguration.class, ShipmentRequestControllerTest.TemporalTestConfiguration.class })
class ShipmentRequestControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TemporalTestConfiguration {

        @Bean
        FakeActivities fakeActivities() {
            return new FakeActivities();
        }

        @Bean(destroyMethod = "close")
        TestWorkflowEnvironment testWorkflowEnvironment(FakeActivities activities) {
            TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
            Worker worker = env.newWorker("shipment");
            worker.registerWorkflowImplementationTypes(ShipmentWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            env.start();
            return env;
        }

        @Bean
        @Primary
        WorkflowClient testWorkflowClient(TestWorkflowEnvironment env) {
            return env.getWorkflowClient();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeActivities activities;

    @Autowired
    private WorkflowClient workflowClient;

    @Autowired
    private TestWorkflowEnvironment testEnvironment;

    @BeforeEach
    void reset() {
        activities.failures.clear();
        activities.authorizeAfterPolls = 2;
        activities.declineAtPoll = null;
    }

    @Test
    void merchantRequest_startsWorkflowAndReturnsCheckoutUrl() throws Exception {
        UUID merchant = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        createAs(merchant, key, body()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.shipmentId").value(ShipmentStarter.shipmentIdFor(merchant, key).toString()))
                .andExpect(jsonPath("$.paymentId").value("pay-1"))
                .andExpect(jsonPath("$.checkoutUrl").value("http://psp/checkout/pay-1"));
    }

    @Test
    void sameIdempotencyKey_returnsSameShipmentWithoutStartingAnotherWorkflow() throws Exception {
        UUID merchant = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        long before = activities.calls.stream().filter("redeem"::equals).count();

        UUID shipmentId = ShipmentStarter.shipmentIdFor(merchant, key);

        createAs(merchant, key, body()).andExpect(status().isAccepted());
        createAs(merchant, key, body()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.shipmentId").value(shipmentId.toString()))
                .andExpect(jsonPath("$.checkoutUrl").value("http://psp/checkout/pay-1"));

        testEnvironment.sleep(java.time.Duration.ofMinutes(1));

        createAs(merchant, key, body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId").value(shipmentId.toString()))
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(activities.calls.stream().filter("redeem"::equals).count() - before).isEqualTo(1);
    }

    @Test
    void sameKeyFromDifferentMerchants_yieldsDifferentShipments() {
        String key = UUID.randomUUID().toString();

        assertThat(ShipmentStarter.shipmentIdFor(UUID.randomUUID(), key))
                .isNotEqualTo(ShipmentStarter.shipmentIdFor(UUID.randomUUID(), key));
    }

    @Test
    void workflowCancelledBeforeCheckout_isConflictProblem() throws Exception {
        activities.failures.put("redeem", FakeActivities.rejected("quote not found"));

        createAs(UUID.randomUUID(), UUID.randomUUID().toString(), body()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("quote not found")));
    }

    @Test
    void onlyMerchantsMayCreate() throws Exception {
        for (String role : new String[] { "OPS", "COURIER", "WAREHOUSE", "WORKFLOW" }) {
            mvc.perform(post("/api/v1/shipments")
                    .header("Authorization", "Bearer " + TestJwts.token(UUID.randomUUID().toString(), role))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/shipments").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidRequests_areBadRequests() throws Exception {
        UUID merchant = UUID.randomUUID();
        createAs(merchant, null, body()).andExpect(status().isBadRequest());
        createAs(merchant, "k", "{}").andExpect(status().isBadRequest());
        createAs(merchant, "k", body().replace("+905551112233", "12")).andExpect(status().isBadRequest());
        createAs(merchant, "k", body().replace("Ada Lovelace", " ")).andExpect(status().isBadRequest());
        createAs(merchant, "k", body().replace("Moda Cd. 12", "")).andExpect(status().isBadRequest());
    }

    private ResultActions createAs(UUID merchant, String key, String body) throws Exception {
        var request = post("/api/v1/shipments")
                .header("Authorization", "Bearer " + TestJwts.token(merchant.toString(), "MERCHANT"))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static String body() {
        return "{\"quoteId\":\"" + UUID.randomUUID() + "\",\"recipientName\":\"Ada Lovelace\","
                + "\"recipientPhone\":\"+905551112233\",\"deliveryAddress\":\"Moda Cd. 12\"}";
    }
}
