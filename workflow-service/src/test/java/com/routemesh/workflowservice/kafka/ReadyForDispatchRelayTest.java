package com.routemesh.workflowservice.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.routemesh.workflowservice.workflow.ShipmentWorkflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowServiceException;
import tools.jackson.databind.json.JsonMapper;

class ReadyForDispatchRelayTest {

    private final WorkflowClient client = mock(WorkflowClient.class);
    private final ShipmentWorkflow workflow = mock(ShipmentWorkflow.class);
    private final UUID shipmentId = UUID.randomUUID();
    private ReadyForDispatchRelay relay;

    @BeforeEach
    void setUp() {
        when(client.newWorkflowStub(ShipmentWorkflow.class, "shipment-" + shipmentId)).thenReturn(workflow);
        relay = new ReadyForDispatchRelay(client, JsonMapper.builder().build());
    }

    @Test
    void readyForDispatchEventSignalsTheShipmentWorkflowWithTheShipmentVersion() {
        relay.onMessage(event("ShipmentStatusChanged", "AT_WAREHOUSE", "READY_FOR_DISPATCH", 4));

        verify(workflow).readyForDispatch(4);
    }

    @Test
    void otherTransitionsAndEventTypesAreIgnored() {
        relay.onMessage(event("ShipmentStatusChanged", "PAID", "AT_WAREHOUSE", 3));
        relay.onMessage(event("ShipmentStatusChanged", "READY_FOR_DISPATCH", "ASSIGNED", 5));
        relay.onMessage(event("ShipmentCreated", null, "READY_FOR_DISPATCH", 1));

        verify(workflow, never()).readyForDispatch(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shipmentWithoutAnOpenWorkflowIsIgnored() {
        doThrow(new WorkflowNotFoundException(io.temporal.api.common.v1.WorkflowExecution.getDefaultInstance(), "ShipmentWorkflow", null)).when(workflow).readyForDispatch(4);

        relay.onMessage(event("ShipmentStatusChanged", "ON_HOLD", "READY_FOR_DISPATCH", 4));

        verify(workflow).readyForDispatch(4);
    }

    @Test
    void temporalOutageIsPropagatedSoKafkaRedelivers() {
        doThrow(new WorkflowServiceException(io.temporal.api.common.v1.WorkflowExecution.getDefaultInstance(), "ShipmentWorkflow", new RuntimeException("unavailable")))
                .when(workflow).readyForDispatch(4);

        assertThatThrownBy(() -> relay.onMessage(event("ShipmentStatusChanged", "ON_HOLD", "READY_FOR_DISPATCH", 4)))
                .isInstanceOf(WorkflowServiceException.class);
    }

    @Test
    void malformedMessageFailsFastForTheErrorHandlerToSkip() {
        assertThatThrownBy(() -> relay.onMessage("not json")).isInstanceOf(RuntimeException.class);
        verify(client, never()).newWorkflowStub(org.mockito.ArgumentMatchers.<Class<ShipmentWorkflow>>any(),
                anyString());
    }

    private String event(String type, String from, String to, long version) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"data\":{\"shipmentId\":\""
                + shipmentId + "\",\"fromStatus\":" + (from == null ? "null" : "\"" + from + "\"")
                + ",\"toStatus\":\"" + to + "\",\"version\":" + version + "}}";
    }
}
