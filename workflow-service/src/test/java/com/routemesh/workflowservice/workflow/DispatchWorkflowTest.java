package com.routemesh.workflowservice.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.routemesh.workflowservice.workflow.FakeDispatchActivities.Behavior;
import com.routemesh.workflowservice.workflow.FakeDispatchActivities.Scenario;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

class DispatchWorkflowTest {

    private static final String TASK_QUEUE = "shipment";

    private final UUID near = UUID.randomUUID();
    private final UUID middle = UUID.randomUUID();
    private final UUID far = UUID.randomUUID();

    private TestWorkflowEnvironment env;
    private FakeDispatchActivities activities;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        activities = new FakeDispatchActivities();
        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DispatchWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void nearestCourierAcceptsAndShipmentIsAssigned() {
        activities.defaultScan = List.of(near, middle);
        activities.scenarios.put(near, new Scenario(Behavior.ACCEPT, 1));

        DispatchResult result = run();

        assertThat(result.outcome()).isEqualTo(DispatchResult.ASSIGNED);
        assertThat(result.courierId()).isEqualTo(near);
        assertThat(activities.lastAssignedCourier).isEqualTo(near);
        assertThat(activities.calls).doesNotContain("offer:" + middle, "hold");
    }

    @Test
    void rejectionMovesToTheNextCourier() {
        activities.defaultScan = List.of(near, middle);
        activities.scenarios.put(near, new Scenario(Behavior.REJECT, 1));
        activities.scenarios.put(middle, new Scenario(Behavior.ACCEPT, 2));

        DispatchResult result = run();

        assertThat(result.courierId()).isEqualTo(middle);
        assertThat(activities.calls).containsSubsequence("offer:" + near, "offer:" + middle, "assign:" + middle);
    }

    @Test
    void silenceExpiresTheOfferAfter60SecondsAndMovesOn() {
        activities.defaultScan = List.of(near, middle);
        activities.scenarios.put(near, new Scenario(Behavior.IGNORE, 0));
        activities.scenarios.put(middle, new Scenario(Behavior.ACCEPT, 1));
        long before = env.currentTimeMillis();

        DispatchResult result = run();

        assertThat(env.currentTimeMillis() - before).isGreaterThanOrEqualTo(Duration.ofSeconds(60).toMillis());
        assertThat(result.courierId()).isEqualTo(middle);
        assertThat(activities.calls).containsSubsequence("offer:" + near, "expire", "offer:" + middle);
    }

    @Test
    void ineligibleCouriersAreSkippedWithoutWaiting() {
        activities.defaultScan = List.of(near, middle);
        activities.scenarios.put(near, new Scenario(Behavior.REFUSE, 0));
        activities.scenarios.put(middle, new Scenario(Behavior.ACCEPT, 1));
        long before = env.currentTimeMillis();

        DispatchResult result = run();

        assertThat(result.courierId()).isEqualTo(middle);
        assertThat(env.currentTimeMillis() - before).isLessThan(Duration.ofSeconds(30).toMillis());
        assertThat(activities.calls).doesNotContain("expire");
    }

    @Test
    void searchWidensFrom5To15KilometresAndTriesEachCourierOncePerPass() {
        activities.defaultScan = List.of(near);
        activities.farScan = List.of(near, far);
        activities.scenarios.put(near, new Scenario(Behavior.REJECT, 1));
        activities.scenarios.put(far, new Scenario(Behavior.ACCEPT, 1));

        DispatchResult result = run();

        assertThat(result.courierId()).isEqualTo(far);
        assertThat(activities.radii.subList(0, 2)).containsExactly(5_000.0, 15_000.0);
        assertThat(activities.calls.stream().filter(("offer:" + near)::equals).count()).isEqualTo(1);
    }

    @Test
    void noCourierAnywhere_rescansEvery30SecondsThenHoldsAfter10Minutes() {
        long before = env.currentTimeMillis();

        DispatchResult result = run();

        assertThat(env.currentTimeMillis() - before).isGreaterThanOrEqualTo(Duration.ofMinutes(10).toMillis());
        assertThat(result.outcome()).isEqualTo(DispatchResult.ON_HOLD);
        assertThat(result.reason()).isEqualTo("NO_COURIER_ACCEPTED");
        assertThat(activities.holdReason).isEqualTo("NO_COURIER_ACCEPTED");
        assertThat(activities.calls.stream().filter("find"::equals).count()).isGreaterThanOrEqualTo(2 * 20);
        assertThat(activities.calls).doesNotContain("assign:" + near);
    }

    @Test
    void courierWhoRejectedIsNotAskedAgainInLaterPasses() {
        activities.defaultScan = List.of(near);
        activities.scenarios.put(near, new Scenario(Behavior.REJECT, 1));

        DispatchResult result = run();

        assertThat(result.outcome()).isEqualTo(DispatchResult.ON_HOLD);
        assertThat(activities.calls.stream().filter(("offer:" + near)::equals).count()).isEqualTo(1);
    }

    @Test
    void courierWhoStayedSilentIsAskedAgainInTheNextPass() {
        activities.defaultScan = List.of(near);
        activities.scenarios.put(near, new Scenario(Behavior.IGNORE, 0));

        DispatchResult result = run();

        assertThat(result.outcome()).isEqualTo(DispatchResult.ON_HOLD);
        assertThat(activities.calls.stream().filter(("offer:" + near)::equals).count()).isGreaterThan(1);
    }

    @Test
    void shipmentThatIsNoLongerReadyIsSkippedUntouched() {
        activities.shipmentStatus = "CANCELLED";
        activities.defaultScan = List.of(near);

        DispatchResult result = run();

        assertThat(result.outcome()).isEqualTo(DispatchResult.SKIPPED);
        assertThat(activities.calls).containsExactly("load");
    }

    @Test
    void rejectedAssignmentReleasesTheCourierAndAborts() {
        activities.defaultScan = List.of(near);
        activities.scenarios.put(near, new Scenario(Behavior.ACCEPT, 1));
        activities.assignFailure = FakeDispatchActivities.rejected("shipment is CANCELLED");

        DispatchResult result = run();

        assertThat(result.outcome()).isEqualTo(DispatchResult.ABORTED);
        assertThat(result.reason()).isEqualTo("shipment is CANCELLED");
        assertThat(activities.calls).containsSubsequence("assign:" + near, "release:" + near);
        assertThat(activities.calls).doesNotContain("hold");
    }

    @Test
    void rejectedHoldAbortsWithoutRetrying() {
        activities.holdFailure = FakeDispatchActivities.rejected("shipment is CANCELLED");

        DispatchResult result = run();

        assertThat(result.outcome()).isEqualTo(DispatchResult.ABORTED);
        assertThat(activities.calls.stream().filter("hold"::equals).count()).isEqualTo(1);
    }

    @Test
    void offerIdsAreDeterministicAndDistinctPerOffer() {
        activities.defaultScan = List.of(near, middle);
        activities.scenarios.put(near, new Scenario(Behavior.REJECT, 1));
        activities.scenarios.put(middle, new Scenario(Behavior.ACCEPT, 1));

        run();

        assertThat(activities.offerCourier).hasSize(2);
    }

    private DispatchResult run() {
        UUID shipmentId = UUID.randomUUID();
        DispatchWorkflow workflow = env.getWorkflowClient().newWorkflowStub(DispatchWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("dispatch-" + shipmentId + "-1")
                        .build());
        return workflow.dispatch(new DispatchInput(shipmentId));
    }
}
