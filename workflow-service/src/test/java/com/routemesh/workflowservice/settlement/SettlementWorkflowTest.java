package com.routemesh.workflowservice.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

class SettlementWorkflowTest {

    private static final String TASK_QUEUE = "shipment";
    private static final long RECEIVED = 1_800_000_000_000L;

    private final UUID shipmentId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID courierId = UUID.randomUUID();

    private TestWorkflowEnvironment env;
    private FakeSettlementActivities activities;

    @BeforeEach
    void setUp() {
        activities = new FakeSettlementActivities(context(1001, 1001, Duration.ofMinutes(200), 240));
        start(activities);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private void start(FakeSettlementActivities fake) {
        if (env != null) {
            env.close();
        }
        activities = fake;
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(SettlementWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        env.start();
    }

    private SettlementContext context(long amount, long escrow, Duration elapsed, int slaMinutes) {
        return new SettlementContext(shipmentId.toString(), merchantId.toString(), courierId.toString(), amount, escrow,
                RECEIVED, RECEIVED + elapsed.toMillis(), slaMinutes);
    }

    private SettlementResult run() {
        return env.getWorkflowClient().newWorkflowStub(SettlementWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE).setWorkflowId("settlement-" + shipmentId).build()).run(shipmentId);
    }

    @Test
    void onTimeDelivery_postsOnlyTheSettlementAndRefundsNothing() {
        SettlementResult result = run();

        assertThat(result).isEqualTo(new SettlementResult(shipmentId.toString(), 750, 180, 71, 0));
        assertThat(activities.calls).containsExactly("load", "ledger:DELIVERY_SETTLEMENT");
        assertThat(activities.posted.get(0).lines()).containsExactly(
                new SettlementTemplates.Line("LIABILITY:ESCROW", true, 1001),
                new SettlementTemplates.Line("LIABILITY:MERCHANT_WALLET:" + merchantId, false, 750),
                new SettlementTemplates.Line("LIABILITY:COURIER_WALLET:" + courierId, false, 180),
                new SettlementTemplates.Line("REVENUE:PLATFORM_FEE", false, 71));
        assertThat(activities.refunds).isEmpty();
    }

    @Test
    void lateDelivery_waivesThePenaltyToTheCustomerAfterTheSettlement() {
        start(new FakeSettlementActivities(context(1001, 1001, Duration.ofMinutes(240 + 40), 240)));

        SettlementResult result = run();

        assertThat(result.slaPenaltyMinor()).isEqualTo(54);
        assertThat(result.merchantMinor()).isEqualTo(750);
        assertThat(result.courierMinor()).isEqualTo(180);
        assertThat(activities.calls).containsExactly("load", "ledger:DELIVERY_SETTLEMENT",
                "ledger:SLA_PENALTY_REFUND", "refund:SLA_PENALTY_REFUND");
        assertThat(activities.posted.get(1).lines()).containsExactly(
                new SettlementTemplates.Line("REVENUE:PLATFORM_FEE", true, 54),
                new SettlementTemplates.Line("ASSET:PSP_CLEARING", false, 54));
        assertThat(activities.refunds).containsExactly(
                new FakeSettlementActivities.Refund("SLA_PENALTY_REFUND", 54, "SLA_PENALTY"));
    }

    @Test
    void veryLateDelivery_isCappedByThePlatformFee() {
        start(new FakeSettlementActivities(context(1001, 1001, Duration.ofHours(10), 240)));

        assertThat(run().slaPenaltyMinor()).isEqualTo(71);
        assertThat(activities.refunds.get(0).amountMinor()).isEqualTo(71);
    }

    @Test
    void expressShipmentUsesItsOwnSlaMinutes() {
        start(new FakeSettlementActivities(context(1001, 1001, Duration.ofMinutes(130), 120)));

        assertThat(run().slaPenaltyMinor()).isEqualTo(18); // 10 dk gecikme = 1 adım
    }

    @Test
    void transientRefundFailure_isRetriedWithoutRepostingTheLedger() {
        FakeSettlementActivities flaky = new FakeSettlementActivities(
                context(1001, 1001, Duration.ofMinutes(240 + 40), 240));
        flaky.transientRefundFailures.set(3);
        start(flaky);

        assertThat(run().slaPenaltyMinor()).isEqualTo(54);

        assertThat(activities.calls.stream().filter("refund:SLA_PENALTY_REFUND"::equals).count()).isEqualTo(4);
        assertThat(activities.calls.stream().filter("ledger:DELIVERY_SETTLEMENT"::equals).count()).isEqualTo(1);
        assertThat(activities.refunds).hasSize(1);
    }

    @Test
    void businessRejections_failTheWorkflowWithoutRetries() {
        FakeSettlementActivities rejecting = new FakeSettlementActivities(context(1001, 1001, Duration.ofMinutes(10), 240));
        rejecting.ledgerFailure = FakeSettlementActivities.rejected("FAILED_PRECONDITION: insufficient escrow");
        start(rejecting);

        assertThatThrownBy(this::run).isInstanceOf(WorkflowException.class);

        assertThat(activities.calls).containsExactly("load", "ledger:DELIVERY_SETTLEMENT");
    }

    @Test
    void notDeliveredOrMismatchedShipments_stopAtLoad() {
        FakeSettlementActivities mismatch = new FakeSettlementActivities(context(1001, 500, Duration.ofMinutes(10), 240));
        mismatch.loadFailure = FakeSettlementActivities.rejected("escrow balance 500 does not match the shipment amount 1001");
        start(mismatch);

        assertThatThrownBy(this::run).isInstanceOf(WorkflowException.class);

        assertThat(activities.calls).containsExactly("load");
    }

    @Test
    void alreadyDistributedEscrow_replaysTheSameIdempotentPosts() {
        start(new FakeSettlementActivities(context(1001, 0, Duration.ofMinutes(10), 240)));

        SettlementResult result = run();

        assertThat(result.merchantMinor()).isEqualTo(750);
        assertThat(activities.calls).containsExactly("load", "ledger:DELIVERY_SETTLEMENT");
    }
}
