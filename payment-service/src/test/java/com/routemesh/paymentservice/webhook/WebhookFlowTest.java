package com.routemesh.paymentservice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.routemesh.paymentservice.FakePspClient.Mode;
import com.routemesh.paymentservice.PaymentTestBase;
import com.routemesh.paymentservice.domain.OperationCommand;
import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.service.PendingTransactionRetrier;
import com.routemesh.paymentservice.webhook.WebhookService.Result;

import tools.jackson.databind.JsonNode;

class WebhookFlowTest extends PaymentTestBase {

    @Autowired
    private PendingTransactionRetrier retrier;

    @Test
    void authorized_movesACreatedPaymentToAuthorized_andEmitsAnEvent() {
        PaymentIntent intent = newIntent();

        Result result = deliver("payment.authorized", intent, 1001);

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(statusOf(intent.id())).isEqualTo("AUTHORIZED");
        List<JsonNode> events = eventsFor(intent.id());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("eventType").asString()).isEqualTo("PaymentStatusChanged");
        assertThat(events.get(0).path("aggregateId").asString()).isEqualTo(intent.id().toString());
        assertThat(events.get(0).path("data").path("fromStatus").asString()).isEqualTo("CREATED");
        assertThat(events.get(0).path("data").path("toStatus").asString()).isEqualTo("AUTHORIZED");
        assertThat(events.get(0).path("data").path("shipmentId").asString()).isEqualTo(intent.shipmentId().toString());
        assertThat(events.get(0).path("data").path("amountMinor").asLong()).isEqualTo(1001);
    }

    @Test
    void webhookOutcomesAndStatusTransitionsAreCounted() {
        double applied = counter("routemesh.payment.webhooks", "outcome", "APPLIED");
        double duplicate = counter("routemesh.payment.webhooks", "outcome", "DUPLICATE");
        double ignored = counter("routemesh.payment.webhooks", "outcome", "IGNORED");
        double unknown = counter("routemesh.payment.webhooks", "outcome", "UNKNOWN_PAYMENT");
        double malformed = counter("routemesh.payment.webhooks", "outcome", "MALFORMED");
        double authorized = counter("routemesh.payment.status.transitions", "from_status", "CREATED",
                "to_status", "AUTHORIZED");
        PaymentIntent intent = newIntent();
        String body = eventBody("evt_" + UUID.randomUUID(), "payment.authorized", intent.pspPaymentId(), 1001, "TRY",
                null);

        deliverRaw(body);
        deliverRaw(body);
        deliver("payment.authorized", intent, 1001); // zaten AUTHORIZED
        deliverRaw(eventBody("evt_" + UUID.randomUUID(), "payment.authorized", "psp_nobody_" + UUID.randomUUID(),
                1001, "TRY", null));
        assertThatThrownBy(() -> deliverRaw("{\"id\":\"\",\"type\":\"payment.authorized\",\"data\":{}}"))
                .isInstanceOf(MalformedWebhookException.class);

        assertThat(counter("routemesh.payment.webhooks", "outcome", "APPLIED") - applied).isEqualTo(1);
        assertThat(counter("routemesh.payment.webhooks", "outcome", "DUPLICATE") - duplicate).isEqualTo(1);
        assertThat(counter("routemesh.payment.webhooks", "outcome", "IGNORED") - ignored).isEqualTo(1);
        assertThat(counter("routemesh.payment.webhooks", "outcome", "UNKNOWN_PAYMENT") - unknown).isEqualTo(1);
        assertThat(counter("routemesh.payment.webhooks", "outcome", "MALFORMED") - malformed).isEqualTo(1);
        assertThat(counter("routemesh.payment.status.transitions", "from_status", "CREATED",
                "to_status", "AUTHORIZED") - authorized).isEqualTo(1);
    }

    @Test
    void duplicateDelivery_isAppliedOnce() {
        PaymentIntent intent = newIntent();
        String body = eventBody("evt_" + UUID.randomUUID(), "payment.authorized", intent.pspPaymentId(), 1001, "TRY",
                null);

        assertThat(deliverRaw(body)).isEqualTo(Result.APPLIED);
        assertThat(deliverRaw(body)).isEqualTo(Result.DUPLICATE);
        assertThat(deliverRaw(body)).isEqualTo(Result.DUPLICATE);

        assertThat(eventsFor(intent.id())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE psp_payment_id = ?", Integer.class,
                intent.pspPaymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM webhook_events WHERE psp_payment_id = ?", String.class,
                intent.pspPaymentId())).isEqualTo("APPLIED");
    }

    @Test
    void authorized_withAnotherEventIdWhileAlreadyAuthorized_isIgnored() {
        PaymentIntent intent = authorizedIntent();

        assertThat(deliver("payment.authorized", intent, 1001)).isEqualTo(Result.IGNORED);

        assertThat(eventsFor(intent.id())).hasSize(1);
    }

    @Test
    void authorized_withAnUnexpectedAmount_failsThePayment_andVoidsTheAuthorization() {
        PaymentIntent intent = newIntent();

        Result result = deliver("payment.authorized", intent, 999);

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(statusOf(intent.id())).isEqualTo("FAILED");
        eventually(() -> psp.calls("void").size() == 1);
        assertThat(paymentService.get(intent.id()).failureReason()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    void declined_failsACreatedPayment_withTheDeclineReason() {
        PaymentIntent intent = newIntent();
        String body = "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"type\":\"payment.declined\",\"created\":1,"
                + "\"data\":{\"payment_id\":\"" + intent.pspPaymentId() + "\",\"decline_reason\":\"card_declined\"}}";

        assertThat(deliverRaw(body)).isEqualTo(Result.APPLIED);

        assertThat(statusOf(intent.id())).isEqualTo("FAILED");
        assertThat(paymentService.get(intent.id()).failureReason()).isEqualTo("card_declined");
        assertThat(deliver("payment.declined", authorizedIntent(), 1001)).isEqualTo(Result.IGNORED);
    }

    @Test
    void lateAuthorization_afterTheWindowExpired_isVoidedAutomatically() {
        PaymentIntent intent = expiredIntent();

        Result result = deliver("payment.authorized", intent, 1001);

        assertThat(result).isEqualTo(Result.LATE_VOID_SCHEDULED);
        eventually(() -> statusOf(intent.id()).equals("VOIDED"));
        assertThat(psp.calls("void")).hasSize(1);
        assertThat(psp.calls("capture")).isEmpty();
        assertThat(psp.calls("refund")).isEmpty();
        List<JsonNode> events = eventsFor(intent.id());
        JsonNode last = events.get(events.size() - 1);
        assertThat(last.path("data").path("fromStatus").asString()).isEqualTo("EXPIRED");
        assertThat(last.path("data").path("toStatus").asString()).isEqualTo("VOIDED");
        assertThat(last.path("data").path("reason").asString()).isEqualTo("LATE_AUTHORIZATION");
    }

    @Test
    void lateAuthorization_deliveredTwiceWithDifferentEventIds_isVoidedOnce() {
        PaymentIntent intent = expiredIntent();

        deliver("payment.authorized", intent, 1001);
        deliver("payment.authorized", intent, 1001);

        eventually(() -> statusOf(intent.id()).equals("VOIDED"));
        assertThat(psp.calls("void")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE intent_id = ?",
                Integer.class, intent.id())).isEqualTo(1);
    }

    @Test
    void lateCapture_afterTheWindowExpired_isRefundedInFull() {
        PaymentIntent intent = expiredIntent();

        Result result = deliver("payment.captured", intent, 1001);

        assertThat(result).isEqualTo(Result.LATE_REFUND_SCHEDULED);
        eventually(() -> statusOf(intent.id()).equals("REFUNDED"));
        assertThat(psp.calls("refund")).hasSize(1);
        assertThat(psp.calls("refund").get(0).amountMinor()).isEqualTo(1001);
        PaymentIntent refunded = paymentService.get(intent.id());
        assertThat(refunded.capturedMinor()).isEqualTo(1001);
        assertThat(refunded.refundedMinor()).isEqualTo(1001);
        List<JsonNode> events = eventsFor(intent.id());
        JsonNode last = events.get(events.size() - 1);
        assertThat(last.path("data").path("toStatus").asString()).isEqualTo("REFUNDED");
        assertThat(last.path("data").path("reason").asString()).isEqualTo("LATE_PAYMENT");
    }

    @Test
    void lateVoid_whenThePspIsDown_isCompletedByTheRetrier() {
        PaymentIntent intent = expiredIntent();
        psp.mode(Mode.UNAVAILABLE);

        deliver("payment.authorized", intent, 1001);

        eventually(() -> "UNKNOWN".equals(jdbc.queryForObject(
                "SELECT status FROM payment_transactions WHERE intent_id = ?", String.class, intent.id())));
        assertThat(statusOf(intent.id())).isEqualTo("EXPIRED");

        psp.mode(Mode.OK);
        jdbc.update("UPDATE payment_transactions SET updated_at = now() - interval '2 minutes' WHERE intent_id = ?",
                intent.id());
        retrier.retry();

        assertThat(statusOf(intent.id())).isEqualTo("VOIDED");
        assertThat(psp.calls("void")).hasSize(2);
        assertThat(psp.calls("void").get(0).idempotencyKey()).isEqualTo(psp.calls("void").get(1).idempotencyKey());
    }

    @Test
    void captured_arrivingBeforeAuthorized_isRetriedLater_withoutBeingRecorded() {
        PaymentIntent intent = newIntent();
        String capturedBody = eventBody("evt_" + UUID.randomUUID(), "payment.captured", intent.pspPaymentId(), 1001,
                "TRY", null);

        assertThat(deliverRaw(capturedBody)).isEqualTo(Result.RETRY_LATER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE psp_payment_id = ?", Integer.class,
                intent.pspPaymentId())).isZero();

        deliver("payment.authorized", intent, 1001);

        assertThat(deliverRaw(capturedBody)).isEqualTo(Result.IGNORED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE psp_payment_id = ?", Integer.class,
                intent.pspPaymentId())).isEqualTo(2);
    }

    @Test
    void capturedWebhook_confirmsACaptureWhoseResultWasUnknown() {
        PaymentIntent intent = authorizedIntent();
        psp.mode(Mode.UNAVAILABLE);
        assertThat(paymentService.capture(new OperationCommand(intent.id(), key(), 0, "TRY", null)).pending())
                .isTrue();

        Result result = deliver("payment.captured", intent, 1001);

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(statusOf(intent.id())).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE intent_id = ?", String.class,
                intent.id())).isEqualTo("SUCCEEDED");
        psp.mode(Mode.OK);
        assertThat(retrier.retry()).isZero();
        assertThat(psp.calls("capture")).hasSize(1);
    }

    @Test
    void voidedWebhook_confirmsAVoidWhoseResultWasUnknown() {
        PaymentIntent intent = authorizedIntent();
        psp.mode(Mode.UNAVAILABLE);
        paymentService.voidPayment(new OperationCommand(intent.id(), key(), 0, "TRY", "TEST"));

        assertThat(deliver("payment.voided", intent, 1001)).isEqualTo(Result.APPLIED);

        assertThat(statusOf(intent.id())).isEqualTo("VOIDED");
    }

    @Test
    void refundSucceededWebhook_confirmsAMatchingRefund_onlyOnce() {
        PaymentIntent intent = capturedIntent();
        psp.mode(Mode.UNAVAILABLE);
        paymentService.refund(new OperationCommand(intent.id(), key(), 300, "TRY", "TEST"));

        String other = eventBody("evt_" + UUID.randomUUID(), "refund.succeeded", intent.pspPaymentId(), 1001, "TRY",
                250L);
        assertThat(deliverRaw(other)).isEqualTo(Result.IGNORED);

        String matching = eventBody("evt_" + UUID.randomUUID(), "refund.succeeded", intent.pspPaymentId(), 1001, "TRY",
                300L);
        assertThat(deliverRaw(matching)).isEqualTo(Result.APPLIED);
        String again = eventBody("evt_" + UUID.randomUUID(), "refund.succeeded", intent.pspPaymentId(), 1001, "TRY",
                300L);
        assertThat(deliverRaw(again)).isEqualTo(Result.IGNORED);

        PaymentIntent refunded = paymentService.get(intent.id());
        assertThat(refunded.status().name()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(refunded.refundedMinor()).isEqualTo(300);
    }

    @Test
    void unknownPaymentAndUnknownEventTypes_areAcknowledged() {
        String unknownPayment = eventBody("evt_" + UUID.randomUUID(), "payment.authorized",
                "psp_" + UUID.randomUUID(), 1, "TRY", null);
        assertThat(deliverRaw(unknownPayment)).isEqualTo(Result.UNKNOWN_PAYMENT);

        PaymentIntent intent = newIntent();
        assertThat(deliver("something.else", intent, 1)).isEqualTo(Result.IGNORED);
        assertThat(statusOf(intent.id())).isEqualTo("CREATED");
    }

    @Test
    void malformedEvents_areRejected() {
        assertThatThrownBy(() -> deliverRaw("{\"type\":\"payment.authorized\",\"data\":{\"payment_id\":\"x\"}}"))
                .isInstanceOf(MalformedWebhookException.class);
        assertThatThrownBy(() -> deliverRaw("{\"id\":\"e\",\"data\":{\"payment_id\":\"x\"}}"))
                .isInstanceOf(MalformedWebhookException.class);
        assertThatThrownBy(() -> deliverRaw("{\"id\":\"e\",\"type\":\"payment.authorized\",\"data\":{}}"))
                .isInstanceOf(MalformedWebhookException.class);
    }
}
