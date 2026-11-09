package com.routemesh.workflowservice.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

class ShipmentWorkflowTest {

    private static final String TASK_QUEUE = "shipment";

    private TestWorkflowEnvironment env;
    private FakeActivities activities;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        activities = new FakeActivities();
        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ShipmentWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void happyPath_runsStepsInOrderAndReturnsCheckoutUrl() {
        ShipmentWorkflow workflow = start();

        CheckoutInfo checkout = workflow.awaitCheckout();
        ShipmentWorkflowResult result = result(workflow);

        assertThat(checkout.checkoutUrl()).isEqualTo("http://psp/checkout/pay-1");
        assertThat(result.status()).isEqualTo("PAID");
        assertThat(activities.calls).containsExactly("redeem", "create", "intent", "getPayment", "getPayment",
                "capture", "escrow", "paid");
    }

    @Test
    void paymentNotAuthorizedIn15Minutes_expiresPaymentAndCompensatesInReverseOrder() {
        activities.authorizeAfterPolls = Integer.MAX_VALUE;
        long before = env.currentTimeMillis();
        ShipmentWorkflow workflow = start();

        ShipmentWorkflowResult result = result(workflow);

        assertThat(env.currentTimeMillis() - before).isGreaterThanOrEqualTo(Duration.ofMinutes(15).toMillis());
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.reason()).isEqualTo("payment EXPIRED");
        assertThat(activities.calls).doesNotContain("capture", "escrow", "paid");
        assertThat(tail(2)).containsExactly("releasePayment", "cancelShipment");
        assertThat(activities.calls).contains("expire");
    }

    @Test
    void authorizationRacingTheTimeout_continuesInsteadOfCancelling() {
        activities.authorizeAfterPolls = Integer.MAX_VALUE;
        activities.expireResult = "AUTHORIZED";

        ShipmentWorkflowResult result = result(start());

        assertThat(result.status()).isEqualTo("PAID");
        assertThat(activities.calls).contains("expire", "capture", "escrow", "paid");
    }

    @Test
    void declinedPayment_cancels() {
        activities.declineAtPoll = "FAILED";

        ShipmentWorkflowResult result = result(start());

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.reason()).isEqualTo("payment FAILED");
        assertThat(tail(2)).containsExactly("releasePayment", "cancelShipment");
    }

    @Test
    void pendingCapture_isPolledUntilCaptured() {
        activities.captureAfterPolls = 2;

        ShipmentWorkflowResult result = result(start());

        assertThat(result.status()).isEqualTo("PAID");
        assertThat(activities.calls).containsSubsequence("capture", "getPayment", "getPayment", "escrow", "paid");
    }

    @Test
    void ledgerRejection_reversesEscrowRefundsAndCancels() {
        activities.failures.put("escrow", FakeActivities.rejected("ledger says no"));

        ShipmentWorkflowResult result = result(start());

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.reason()).isEqualTo("ledger says no");
        assertThat(tail(3)).containsExactly("reverseEscrow", "releasePayment", "cancelShipment");
        assertThat(activities.calls).doesNotContain("paid");
    }

    @Test
    void markPaidRejection_compensatesEverything() {
        activities.failures.put("paid", FakeActivities.rejected("cannot mark paid"));

        ShipmentWorkflowResult result = result(start());

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(tail(3)).containsExactly("reverseEscrow", "releasePayment", "cancelShipment");
    }

    @Test
    void redeemRejection_cancelsWithoutCompensationAndCheckoutUpdateFails() {
        activities.failures.put("redeem", FakeActivities.rejected("quote not found"));
        ShipmentWorkflow workflow = start();

        assertThatThrownBy(workflow::awaitCheckout)
                .isInstanceOf(WorkflowUpdateException.class)
                .hasRootCauseInstanceOf(ApplicationFailure.class)
                .hasStackTraceContaining("quote not found");
        ShipmentWorkflowResult result = result(workflow);

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(activities.calls).containsExactly("redeem");
    }

    @Test
    void createShipmentRejection_compensatesTheShipmentOnly() {
        activities.failures.put("create", FakeActivities.rejected("invalid shipment"));

        ShipmentWorkflowResult result = result(start());

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(activities.calls).containsExactly("redeem", "create", "cancelShipment");
    }

    @Test
    void transientFailures_areRetriedTransparently() {
        activities.transientCreateFailures.set(2);

        ShipmentWorkflowResult result = result(start());

        assertThat(result.status()).isEqualTo("PAID");
        assertThat(activities.calls.stream().filter("create"::equals).count()).isEqualTo(3);
    }

    @Test
    void compensationActivities_areRetriedUntilTheySucceed() {
        AtomicInteger attempts = new AtomicInteger();
        FakeActivities flaky = new FakeActivities() {
            @Override
            public void releasePayment(String paymentId, long amountMinor, String reason) {
                if (attempts.incrementAndGet() < 4) {
                    throw new IllegalStateException("payment-service down");
                }
                super.releasePayment(paymentId, amountMinor, reason);
            }
        };
        flaky.failures.put("escrow", FakeActivities.rejected("ledger says no"));
        restartWith(flaky);

        ShipmentWorkflowResult result = result(start());

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(attempts.get()).isEqualTo(4);
        assertThat(tail(3)).containsExactly("reverseEscrow", "releasePayment", "cancelShipment");
    }

    @Test
    void cancellingTheWorkflowWhileWaitingForPayment_runsCompensations() {
        activities.authorizeAfterPolls = Integer.MAX_VALUE;
        ShipmentWorkflow workflow = start();
        workflow.awaitCheckout();

        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        stub.cancel();

        assertThatThrownBy(() -> stub.getResult(ShipmentWorkflowResult.class)).isInstanceOf(WorkflowException.class);
        assertThat(tail(2)).containsExactly("releasePayment", "cancelShipment");
    }

    @Test
    void sameWorkflowIdCannotBeStartedTwice() {
        UUID shipmentId = UUID.randomUUID();
        start(shipmentId);

        assertThatThrownBy(() -> start(shipmentId))
                .isInstanceOf(io.temporal.client.WorkflowExecutionAlreadyStarted.class);
    }

    private void restartWith(FakeActivities replacement) {
        env.close();
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ShipmentWorkflowImpl.class);
        worker.registerActivitiesImplementations(replacement);
        env.start();
        activities = replacement;
    }

    private ShipmentWorkflow start() {
        return start(UUID.randomUUID());
    }

    private ShipmentWorkflow start(UUID shipmentId) {
        ShipmentWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ShipmentWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("shipment-" + shipmentId)
                        .build());
        WorkflowClient.start(workflow::run, new ShipmentWorkflowInput(shipmentId, UUID.randomUUID(),
                UUID.randomUUID(), "Ada Lovelace", "+905551112233", "Moda Cd. 12"));
        return workflow;
    }

    private static ShipmentWorkflowResult result(ShipmentWorkflow workflow) {
        return WorkflowStub.fromTyped(workflow).getResult(ShipmentWorkflowResult.class);
    }

    private List<String> tail(int n) {
        List<String> calls = activities.calls;
        return calls.subList(calls.size() - n, calls.size());
    }
}
