package com.routemesh.workflowservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.routemesh.workflowservice.config.TemporalProperties;
import com.routemesh.workflowservice.settlement.SettlementWorkflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import tools.jackson.databind.json.JsonMapper;

class SettlementRelayTest {

    private static final String TASK_QUEUE = "shipment";

    private final UUID shipmentId = UUID.randomUUID();

    @Test
    void deliveredEventStartsTheSettlementWorkflowOnceWithADeterministicId() {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = env.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(BlockingSettlement.class);
            env.start();
            SettlementRelay relay = relay(env.getWorkflowClient());

            relay.onMessage(event("ShipmentStatusChanged", "DELIVERED"));
            relay.onMessage(event("ShipmentStatusChanged", "DELIVERED")); // Kafka yeniden teslimi

            var execution = env.getWorkflowClient().newUntypedWorkflowStub("settlement-" + shipmentId)
                    .describe().getExecution();
            assertThat(execution.getWorkflowId()).isEqualTo("settlement-" + shipmentId);
        } finally {
            env.close();
        }
    }

    @Test
    void otherTransitionsAndEventTypesAreIgnored() {
        WorkflowClient client = mock(WorkflowClient.class);
        SettlementRelay relay = relay(client);

        relay.onMessage(event("ShipmentStatusChanged", "ASSIGNED"));
        relay.onMessage(event("ShipmentStatusChanged", "CANCELLED"));
        relay.onMessage(event("ShipmentCreated", "DELIVERED"));

        verifyNoInteractions(client);
    }

    @Test
    void temporalOutageIsPropagatedSoKafkaRedelivers() {
        WorkflowClient client = mock(WorkflowClient.class);
        when(client.newWorkflowStub(any(Class.class), any(WorkflowOptions.class)))
                .thenThrow(new WorkflowServiceException(
                        io.temporal.api.common.v1.WorkflowExecution.getDefaultInstance(), "SettlementWorkflow",
                        new RuntimeException("unavailable")));

        assertThatThrownBy(() -> relay(client).onMessage(event("ShipmentStatusChanged", "DELIVERED")))
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

    private String event(String type, String to) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"data\":{\"shipmentId\":\""
                + shipmentId + "\",\"toStatus\":\"" + to + "\",\"version\":6}}";
    }

    /** Başlatma denemesini gözlemlemek için bitmeyen bir iş akışı. */
    public static class BlockingSettlement implements SettlementWorkflow {

        @Override
        public com.routemesh.workflowservice.settlement.SettlementResult run(UUID shipmentId) {
            io.temporal.workflow.Workflow.await(() -> false);
            return null;
        }
    }
}
