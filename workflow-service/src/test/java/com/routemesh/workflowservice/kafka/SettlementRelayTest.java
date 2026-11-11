package com.routemesh.workflowservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.routemesh.workflowservice.config.TemporalProperties;
import com.routemesh.workflowservice.settlement.SettlementInput;
import com.routemesh.workflowservice.settlement.SettlementKind;
import com.routemesh.workflowservice.settlement.SettlementResult;
import com.routemesh.workflowservice.settlement.SettlementWorkflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import tools.jackson.databind.json.JsonMapper;

class SettlementRelayTest {

    private static final String TASK_QUEUE = "shipment";
    static final List<SettlementInput> STARTED = new CopyOnWriteArrayList<>();

    private final UUID shipmentId = UUID.randomUUID();

    @Test
    void terminalEventStartsTheSettlementWorkflowOnceWithADeterministicId() {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = env.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(BlockingSettlement.class);
            env.start();
            SettlementRelay relay = relay(env.getWorkflowClient());

            relay.onMessage(event("ShipmentStatusChanged", "OUT_FOR_DELIVERY", "DELIVERED"));
            relay.onMessage(event("ShipmentStatusChanged", "OUT_FOR_DELIVERY", "DELIVERED")); // Kafka yeniden teslimi

            var execution = env.getWorkflowClient().newUntypedWorkflowStub("settlement-" + shipmentId)
                    .describe().getExecution();
            assertThat(execution.getWorkflowId()).isEqualTo("settlement-" + shipmentId);
        } finally {
            env.close();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "OUT_FOR_DELIVERY,DELIVERED,DELIVERY",
            "RETURN_TO_WAREHOUSE,CANCELLED,RETURN",
            "OUT_FOR_DELIVERY,LOST,LOST",
            "AT_WAREHOUSE,LOST,LOST",
            "RETURN_TO_WAREHOUSE,LOST,LOST",
            "PAID,CANCELLED,CANCEL",
            "READY_FOR_DISPATCH,CANCELLED,CANCEL",
            "ASSIGNED,CANCELLED,CANCEL",
            "ON_HOLD,CANCELLED,CANCEL",
            "DAMAGED,CANCELLED,CANCEL"})
    void eventsMapToTheRightSettlementKind(String from, String to, SettlementKind expected) {
        assertThat(SettlementRelay.kindFor(from, to)).isEqualTo(expected);
    }

    @Test
    void theStartedWorkflowCarriesTheKind() {
        STARTED.clear();
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = env.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(RecordingSettlement.class);
            env.start();

            relay(env.getWorkflowClient()).onMessage(event("ShipmentStatusChanged", "RETURN_TO_WAREHOUSE", "CANCELLED"));
            env.sleep(java.time.Duration.ofSeconds(1));

            assertThat(STARTED).containsExactly(new SettlementInput(shipmentId, SettlementKind.RETURN));
        } finally {
            env.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"PAID,ASSIGNED", "READY_FOR_DISPATCH,ASSIGNED", "ASSIGNED,OUT_FOR_DELIVERY", "OUT_FOR_DELIVERY,DELIVERY_FAILED",
            "DELIVERY_FAILED,RETURN_TO_WAREHOUSE", "PENDING_PAYMENT,CANCELLED", "PENDING_PAYMENT,PAID"})
    void transitionsWithoutMoneyMovementAreIgnored(String from, String to) {
        WorkflowClient client = mock(WorkflowClient.class);

        relay(client).onMessage(event("ShipmentStatusChanged", from, to));

        verifyNoInteractions(client);
    }

    @Test
    void otherEventTypesAreIgnored() {
        WorkflowClient client = mock(WorkflowClient.class);

        relay(client).onMessage(event("ShipmentCreated", "OUT_FOR_DELIVERY", "DELIVERED"));

        verifyNoInteractions(client);
    }

    @Test
    void aCancellationFromAnUnknownStateIsNeverSettledWithAGuess() {
        WorkflowClient client = mock(WorkflowClient.class);
        SettlementRelay relay = relay(client);

        assertThatThrownBy(() -> relay.onMessage(event("ShipmentStatusChanged", "DELIVERED", "CANCELLED")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relay.onMessage(
                "{\"eventType\":\"ShipmentStatusChanged\",\"data\":{\"shipmentId\":\"" + shipmentId
                        + "\",\"toStatus\":\"CANCELLED\"}}")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void temporalOutageIsPropagatedSoKafkaRedelivers() {
        WorkflowClient client = mock(WorkflowClient.class);
        when(client.newWorkflowStub(any(Class.class), any(WorkflowOptions.class)))
                .thenThrow(new WorkflowServiceException(
                        io.temporal.api.common.v1.WorkflowExecution.getDefaultInstance(), "SettlementWorkflow",
                        new RuntimeException("unavailable")));

        assertThatThrownBy(() -> relay(client).onMessage(event("ShipmentStatusChanged", "OUT_FOR_DELIVERY", "DELIVERED")))
                .isInstanceOf(WorkflowServiceException.class);
    }

    @Test
    void malformedMessageFailsFastForTheErrorHandlerToSkip() {
        WorkflowClient client = mock(WorkflowClient.class);

        assertThatThrownBy(() -> relay(client).onMessage("not json")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(client);
    }

    private SettlementRelay relay(WorkflowClient client) {
        return new SettlementRelay(client, new TemporalProperties("localhost:7233", "default", TASK_QUEUE, false),
                JsonMapper.builder().build());
    }

    private String event(String type, String from, String to) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"data\":{\"shipmentId\":\""
                + shipmentId + "\",\"fromStatus\":\"" + from + "\",\"toStatus\":\"" + to + "\",\"version\":6}}";
    }

    /** Başlatma denemesini gözlemlemek için bitmeyen bir iş akışı. */
    public static class BlockingSettlement implements SettlementWorkflow {

        @Override
        public SettlementResult run(SettlementInput input) {
            io.temporal.workflow.Workflow.await(() -> false);
            return null;
        }
    }

    /** Aldığı girdiyi kaydeden iş akışı. */
    public static class RecordingSettlement implements SettlementWorkflow {

        @Override
        public SettlementResult run(SettlementInput input) {
            STARTED.add(input);
            return null;
        }
    }
}
