package com.routemesh.paymentservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.routemesh.paymentservice.FakePspClient.Mode;
import com.routemesh.paymentservice.PaymentTestBase;
import com.routemesh.paymentservice.domain.CommandResult;
import com.routemesh.paymentservice.domain.CreatePaymentCommand;
import com.routemesh.paymentservice.domain.OperationCommand;
import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.service.PaymentException.Reason;
import com.routemesh.paymentservice.webhook.WebhookService.Result;

import tools.jackson.databind.JsonNode;

class PaymentServiceTest extends PaymentTestBase {

    @Autowired
    private PendingTransactionRetrier retrier;

    @Autowired
    private IntentExpirySweeper sweeper;

    @Test
    void create_persistsTheIntentAndCallsThePspWithAPrefixedIdempotencyKey() {
        String key = key();
        UUID shipment = UUID.randomUUID();

        CommandResult result = paymentService.create(new CreatePaymentCommand(key, shipment, 1001, "TRY"));

        PaymentIntent intent = result.payment();
        assertThat(result.replayed()).isFalse();
        assertThat(result.pending()).isFalse();
        assertThat(intent.status().name()).isEqualTo("CREATED");
        assertThat(intent.shipmentId()).isEqualTo(shipment);
        assertThat(intent.checkoutUrl()).startsWith("https://psp.test/checkout/");
        assertThat(Duration.between(intent.createdAt(), intent.expiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(psp.calls("create")).hasSize(1);
        assertThat(psp.calls("create").get(0).idempotencyKey()).isEqualTo("create:" + key);
        assertThat(psp.calls("create").get(0).amountMinor()).isEqualTo(1001);
    }

    @Test
    void create_sameKeyAndPayload_isReplayedWithoutCallingThePspAgain() {
        CreatePaymentCommand command = new CreatePaymentCommand(key(), UUID.randomUUID(), 500, "TRY");
        PaymentIntent first = paymentService.create(command).payment();

        CommandResult second = paymentService.create(command);

        assertThat(second.replayed()).isTrue();
        assertThat(second.payment().id()).isEqualTo(first.id());
        assertThat(psp.calls("create")).hasSize(1);
        assertThat(count("payment_intents WHERE idempotency_key = ?", command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void create_sameKeyWithDifferentPayload_isConflict() {
        String key = key();
        UUID shipment = UUID.randomUUID();
        paymentService.create(new CreatePaymentCommand(key, shipment, 500, "TRY"));

        assertReason(() -> paymentService.create(new CreatePaymentCommand(key, shipment, 600, "TRY")),
                Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void create_invalidInputs_areRejectedWithoutCallingThePsp() {
        assertReason(() -> paymentService.create(new CreatePaymentCommand(" ", UUID.randomUUID(), 5, "TRY")),
                Reason.INVALID_REQUEST);
        assertReason(() -> paymentService.create(new CreatePaymentCommand(key(), UUID.randomUUID(), 0, "TRY")),
                Reason.INVALID_REQUEST);
        assertReason(() -> paymentService.create(new CreatePaymentCommand(key(), UUID.randomUUID(), 5, "USD")),
                Reason.INVALID_REQUEST);
        assertThat(psp.calls()).isEmpty();
    }

    @Test
    void create_pspFailures_areReportedAndLeaveNoIntentBehind() {
        String rejected = key();
        psp.mode(Mode.REJECT);
        assertReason(() -> paymentService.create(new CreatePaymentCommand(rejected, UUID.randomUUID(), 5, "TRY")),
                Reason.PSP_REJECTED);

        String unavailable = key();
        psp.mode(Mode.UNAVAILABLE);
        assertReason(() -> paymentService.create(new CreatePaymentCommand(unavailable, UUID.randomUUID(), 5, "TRY")),
                Reason.PSP_UNAVAILABLE);

        assertThat(count("payment_intents WHERE idempotency_key IN (?, ?)", rejected, unavailable)).isZero();
    }

    @Test
    void capture_movesTheIntentToCaptured_usingTheTransactionIdAsPspKey() {
        PaymentIntent intent = authorizedIntent();

        CommandResult result = paymentService.capture(op(intent, key()));

        assertThat(result.payment().status().name()).isEqualTo("CAPTURED");
        assertThat(result.pending()).isFalse();
        assertThat(result.payment().capturedMinor()).isEqualTo(1001);
        UUID transactionId = jdbc.queryForObject("SELECT id FROM payment_transactions WHERE intent_id = ? "
                + "AND type = 'CAPTURE'", UUID.class, intent.id());
        assertThat(psp.calls("capture")).hasSize(1);
        assertThat(psp.calls("capture").get(0).idempotencyKey()).isEqualTo(transactionId.toString());
        JsonNode event = lastEvent(intent.id());
        assertThat(event.path("data").path("fromStatus").asString()).isEqualTo("AUTHORIZED");
        assertThat(event.path("data").path("toStatus").asString()).isEqualTo("CAPTURED");
    }

    @Test
    void capture_sameKeyAgain_isReplayedWithoutAnotherPspCall() {
        PaymentIntent intent = authorizedIntent();
        String key = key();
        paymentService.capture(op(intent, key));

        CommandResult replay = paymentService.capture(op(intent, key));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.pending()).isFalse();
        assertThat(replay.payment().status().name()).isEqualTo("CAPTURED");
        assertThat(psp.calls("capture")).hasSize(1);
    }

    @Test
    void capture_whenNotAuthorized_isRejected() {
        PaymentIntent created = newIntent();
        PaymentIntent captured = capturedIntent();

        assertReason(() -> paymentService.capture(op(created, key())), Reason.ILLEGAL_STATE);
        assertReason(() -> paymentService.capture(op(captured, key())), Reason.ILLEGAL_STATE);
        assertReason(() -> paymentService.capture(op(newExpired(), key())), Reason.ILLEGAL_STATE);
    }

    @Test
    void capture_unknownPayment_isNotFound() {
        assertReason(() -> paymentService.capture(new OperationCommand(UUID.randomUUID(), key(), 0, "TRY", null)),
                Reason.NOT_FOUND);
    }

    @Test
    void capture_rejectedByThePsp_leavesTheIntentAuthorized_andAllowsANewAttempt() {
        PaymentIntent intent = authorizedIntent();
        psp.mode(Mode.REJECT);

        assertReason(() -> paymentService.capture(op(intent, key())), Reason.PSP_REJECTED);

        assertThat(statusOf(intent.id())).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE intent_id = ? "
                + "AND type = 'CAPTURE'", String.class, intent.id())).isEqualTo("FAILED");

        psp.mode(Mode.OK);
        assertThat(paymentService.capture(op(intent, key())).payment().status().name()).isEqualTo("CAPTURED");
    }

    @Test
    void capture_whenThePspIsUnavailable_staysPending_andTheRetrierFinishesItWithTheSameKey() {
        PaymentIntent intent = authorizedIntent();
        String key = key();
        psp.mode(Mode.UNAVAILABLE);

        CommandResult pending = paymentService.capture(op(intent, key));

        assertThat(pending.pending()).isTrue();
        assertThat(pending.payment().status().name()).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE intent_id = ? "
                + "AND type = 'CAPTURE'", String.class, intent.id())).isEqualTo("UNKNOWN");

        psp.mode(Mode.OK);
        ageOpenTransactions(intent.id());
        assertThat(retrier.retry()).isGreaterThanOrEqualTo(1);

        assertThat(statusOf(intent.id())).isEqualTo("CAPTURED");
        assertThat(psp.calls("capture")).hasSize(2);
        assertThat(psp.calls("capture").get(0).idempotencyKey())
                .isEqualTo(psp.calls("capture").get(1).idempotencyKey());
        CommandResult replay = paymentService.capture(op(intent, key));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.pending()).isFalse();
    }

    @Test
    void capture_whileAnotherCaptureIsOpen_isRejected() {
        PaymentIntent intent = authorizedIntent();
        psp.mode(Mode.UNAVAILABLE);
        paymentService.capture(op(intent, key()));

        assertReason(() -> paymentService.capture(op(intent, key())), Reason.ILLEGAL_STATE);
    }

    @Test
    void voidPayment_releasesTheAuthorization() {
        PaymentIntent intent = authorizedIntent();
        String key = key();

        CommandResult result = paymentService.voidPayment(
                new OperationCommand(intent.id(), key, 0, "TRY", "NO_COURIER_AVAILABLE"));

        assertThat(result.payment().status().name()).isEqualTo("VOIDED");
        assertThat(result.payment().failureReason()).isEqualTo("NO_COURIER_AVAILABLE");
        assertThat(psp.calls("void")).hasSize(1);
        assertThat(lastEvent(intent.id()).path("data").path("reason").asString()).isEqualTo("NO_COURIER_AVAILABLE");
        assertThat(paymentService.voidPayment(
                new OperationCommand(intent.id(), key, 0, "TRY", "NO_COURIER_AVAILABLE")).replayed()).isTrue();
    }

    @Test
    void voidPayment_onlyWorksOnAnAuthorizedPayment() {
        assertReason(() -> paymentService.voidPayment(op(newIntent(), key())), Reason.ILLEGAL_STATE);
        assertReason(() -> paymentService.voidPayment(op(capturedIntent(), key())), Reason.ILLEGAL_STATE);
    }

    @Test
    void refund_partialThenFull_tracksTheRefundedAmount() {
        PaymentIntent intent = capturedIntent();

        CommandResult partial = paymentService.refund(refund(intent, key(), 400));
        assertThat(partial.payment().status().name()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(partial.payment().refundedMinor()).isEqualTo(400);

        CommandResult rest = paymentService.refund(refund(intent, key(), 601));
        assertThat(rest.payment().status().name()).isEqualTo("REFUNDED");
        assertThat(rest.payment().refundedMinor()).isEqualTo(1001);
        assertThat(psp.calls("refund")).extracting(FakePspCallAmount::of).containsExactly(400L, 601L);
        assertThat(lastEvent(intent.id()).path("data").path("refundedMinor").asLong()).isEqualTo(1001);
        assertReason(() -> paymentService.refund(refund(intent, key(), 1)), Reason.ILLEGAL_STATE);
    }

    @Test
    void refund_cannotExceedTheCapturedAmount_includingPendingRefunds() {
        PaymentIntent intent = capturedIntent();
        paymentService.refund(refund(intent, key(), 400));

        assertReason(() -> paymentService.refund(refund(intent, key(), 700)), Reason.ILLEGAL_STATE);

        psp.mode(Mode.UNAVAILABLE);
        CommandResult pending = paymentService.refund(refund(intent, key(), 500));
        assertThat(pending.pending()).isTrue();
        assertReason(() -> paymentService.refund(refund(intent, key(), 200)), Reason.ILLEGAL_STATE);
        assertThat(paymentService.refund(refund(intent, key(), 101)).pending()).isTrue();
    }

    @Test
    void refund_sameKey_isReplayedOrConflicts() {
        PaymentIntent intent = capturedIntent();
        String key = key();
        paymentService.refund(refund(intent, key, 300));

        assertThat(paymentService.refund(refund(intent, key, 300)).replayed()).isTrue();
        assertReason(() -> paymentService.refund(refund(intent, key, 301)), Reason.IDEMPOTENCY_CONFLICT);
        assertThat(psp.calls("refund")).hasSize(1);
    }

    @Test
    void refund_requiresACapturedPayment_andValidInputs() {
        assertReason(() -> paymentService.refund(refund(authorizedIntent(), key(), 100)), Reason.ILLEGAL_STATE);
        PaymentIntent captured = capturedIntent();
        assertReason(() -> paymentService.refund(refund(captured, key(), 0)), Reason.INVALID_REQUEST);
        assertReason(() -> paymentService.refund(new OperationCommand(captured.id(), key(), 100, "USD", null)),
                Reason.INVALID_REQUEST);
    }

    @Test
    void expire_movesACreatedPaymentToExpired_andIsIdempotent() {
        PaymentIntent intent = newIntent();

        CommandResult first = paymentService.expire(intent.id());
        CommandResult second = paymentService.expire(intent.id());

        assertThat(first.payment().status().name()).isEqualTo("EXPIRED");
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(lastEvent(intent.id()).path("data").path("reason").asString()).isEqualTo("PAYMENT_WINDOW_ELAPSED");
        assertThat(eventsFor(intent.id())).hasSize(1);
    }

    @Test
    void expire_onAnAuthorizedPayment_isRejected() {
        assertReason(() -> paymentService.expire(authorizedIntent().id()), Reason.ILLEGAL_STATE);
        assertReason(() -> paymentService.expire(UUID.randomUUID()), Reason.NOT_FOUND);
    }

    @Test
    void sweeper_expiresCreatedPaymentsPastTheirWindowPlusGrace_only() {
        PaymentIntent overdue = newIntent();
        PaymentIntent withinGrace = newIntent();
        PaymentIntent authorized = authorizedIntent();
        setExpiry(overdue.id(), "5 minutes");
        setExpiry(withinGrace.id(), "30 seconds");
        setExpiry(authorized.id(), "5 minutes");

        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);

        assertThat(statusOf(overdue.id())).isEqualTo("EXPIRED");
        assertThat(statusOf(withinGrace.id())).isEqualTo("CREATED");
        assertThat(statusOf(authorized.id())).isEqualTo("AUTHORIZED");
    }

    @Test
    void expireAndAuthorizeRace_hasExactlyOneConsistentWinner() throws Exception {
        for (int i = 0; i < 15; i++) {
            psp.reset();
            PaymentIntent intent = newIntent();
            List<Callable<String>> tasks = List.of(
                    () -> {
                        try {
                            paymentService.expire(intent.id());
                            return "EXPIRED";
                        } catch (PaymentException e) {
                            return e.getReason().name();
                        }
                    },
                    () -> deliver("payment.authorized", intent, 1001).name());

            List<String> outcomes = runConcurrently(tasks);

            if (outcomes.get(0).equals("EXPIRED")) {
                assertThat(outcomes.get(1)).isEqualTo(Result.LATE_VOID_SCHEDULED.name());
                eventually(() -> statusOf(intent.id()).equals("VOIDED"));
                assertThat(psp.calls("void")).hasSize(1);
            } else {
                assertThat(outcomes.get(0)).isEqualTo(Reason.ILLEGAL_STATE.name());
                assertThat(outcomes.get(1)).isEqualTo(Result.APPLIED.name());
                assertThat(statusOf(intent.id())).isEqualTo("AUTHORIZED");
                assertThat(psp.calls("void")).isEmpty();
            }
        }
    }

    @Test
    void concurrentCaptureRequests_withDifferentKeys_issueExactlyOnePspCapture() throws Exception {
        PaymentIntent intent = authorizedIntent();
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> {
                try {
                    paymentService.capture(op(intent, key()));
                    return "OK";
                } catch (PaymentException e) {
                    return e.getReason().name();
                }
            });
        }

        List<String> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(psp.calls("capture")).hasSize(1);
        assertThat(statusOf(intent.id())).isEqualTo("CAPTURED");
    }

    @Test
    void concurrentSameKeyCreates_produceOneIntent() throws Exception {
        CreatePaymentCommand command = new CreatePaymentCommand(key(), UUID.randomUUID(), 700, "TRY");
        List<Callable<CommandResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> paymentService.create(command));
        }

        List<CommandResult> results = runConcurrently(tasks);

        assertThat(results.stream().map(r -> r.payment().id()).distinct().count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(count("payment_intents WHERE idempotency_key = ?", command.idempotencyKey())).isEqualTo(1);
    }

    private PaymentIntent newExpired() {
        return expiredIntent();
    }

    private static OperationCommand op(PaymentIntent intent, String key) {
        return new OperationCommand(intent.id(), key, 0, "TRY", null);
    }

    private static OperationCommand refund(PaymentIntent intent, String key, long amount) {
        return new OperationCommand(intent.id(), key, amount, "TRY", "TEST_REFUND");
    }

    private void ageOpenTransactions(UUID intentId) {
        jdbc.update("UPDATE payment_transactions SET updated_at = now() - interval '2 minutes' "
                + "WHERE intent_id = ? AND status IN ('PENDING', 'UNKNOWN')", intentId);
    }

    private void setExpiry(UUID intentId, String pastInterval) {
        jdbc.update("UPDATE payment_intents SET expires_at = now() - ?::interval WHERE id = ?", pastInterval,
                intentId);
    }

    private JsonNode lastEvent(UUID paymentId) {
        List<JsonNode> events = eventsFor(paymentId);
        return events.get(events.size() - 1);
    }

    private int count(String fromAndWhere, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromAndWhere, Integer.class, args);
    }

    private static void assertReason(Runnable action, Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(PaymentException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class FakePspCallAmount {

        static long of(com.routemesh.paymentservice.FakePspClient.Call call) {
            return call.amountMinor();
        }
    }
}
