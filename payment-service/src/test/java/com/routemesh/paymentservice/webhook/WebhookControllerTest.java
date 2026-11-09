package com.routemesh.paymentservice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.routemesh.paymentservice.PaymentTestBase;
import com.routemesh.paymentservice.domain.PaymentIntent;

@AutoConfigureMockMvc
class WebhookControllerTest extends PaymentTestBase {

    private static final String URL = "/api/v1/webhooks/psp";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validSignature_appliesTheEvent() throws Exception {
        PaymentIntent intent = newIntent();
        String body = body("payment.authorized", intent, 1001);

        send(body, signature(SECRET, now(), body)).andExpect(status().isOk());

        assertThat(statusOf(intent.id())).isEqualTo("AUTHORIZED");
    }

    @Test
    void duplicateDelivery_isAcknowledgedWithoutReapplying() throws Exception {
        PaymentIntent intent = newIntent();
        String body = body("payment.authorized", intent, 1001);

        send(body, signature(SECRET, now(), body)).andExpect(status().isOk());
        send(body, signature(SECRET, now(), body)).andExpect(status().isOk());

        assertThat(eventsFor(intent.id())).hasSize(1);
    }

    @Test
    void missingSignature_isUnauthorized_andNothingChanges() throws Exception {
        PaymentIntent intent = newIntent();

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("payment.authorized", intent, 1001).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(intent.id())).isEqualTo("CREATED");
        assertThat(webhookRows(intent)).isZero();
    }

    @Test
    void wrongSecretOrTamperedBody_isUnauthorized() throws Exception {
        PaymentIntent intent = newIntent();
        String body = body("payment.authorized", intent, 1001);

        send(body, signature("some-other-secret", now(), body)).andExpect(status().isUnauthorized());
        send(body.replace("1001", "1"), signature(SECRET, now(), body)).andExpect(status().isUnauthorized());

        assertThat(statusOf(intent.id())).isEqualTo("CREATED");
        assertThat(webhookRows(intent)).isZero();
    }

    @Test
    void staleSignature_isUnauthorized_soCapturedRequestsCannotBeReplayed() throws Exception {
        PaymentIntent intent = newIntent();
        String body = body("payment.authorized", intent, 1001);

        send(body, signature(SECRET, now() - 600, body)).andExpect(status().isUnauthorized());

        assertThat(statusOf(intent.id())).isEqualTo("CREATED");
    }

    @Test
    void outOfOrderEvent_isAnsweredWithConflictSoThePspRetriesLater() throws Exception {
        PaymentIntent intent = newIntent();
        String body = body("payment.captured", intent, 1001);

        send(body, signature(SECRET, now(), body)).andExpect(status().isConflict());

        assertThat(webhookRows(intent)).isZero();
    }

    @Test
    void malformedPayloads_areBadRequests_evenWithAValidSignature() throws Exception {
        String notJson = "this is not json";
        send(notJson, signature(SECRET, now(), notJson)).andExpect(status().isBadRequest());

        String missingFields = "{\"type\":\"payment.authorized\"}";
        send(missingFields, signature(SECRET, now(), missingFields)).andExpect(status().isBadRequest());
    }

    @Test
    void nonJsonContentType_isRejected() throws Exception {
        PaymentIntent intent = newIntent();
        String body = body("payment.authorized", intent, 1001);

        mockMvc.perform(post(URL).contentType(MediaType.TEXT_PLAIN)
                        .header(WebhookController.SIGNATURE_HEADER, signature(SECRET, now(), body))
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void unknownPayment_isAcknowledged() throws Exception {
        String body = eventBody("evt_" + UUID.randomUUID(), "payment.authorized", "psp_unknown_" + UUID.randomUUID(),
                1, "TRY", null);

        send(body, signature(SECRET, now(), body)).andExpect(status().isOk());
    }

    private ResultActions send(String body, String signature) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                .header(WebhookController.SIGNATURE_HEADER, signature)
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String body(String type, PaymentIntent intent, long amount) {
        return eventBody("evt_" + UUID.randomUUID(), type, intent.pspPaymentId(), amount, "TRY", null);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private int webhookRows(PaymentIntent intent) {
        return jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE psp_payment_id = ?", Integer.class,
                intent.pspPaymentId());
    }
}
