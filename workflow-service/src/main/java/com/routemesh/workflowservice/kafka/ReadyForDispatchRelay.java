package com.routemesh.workflowservice.kafka;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.routemesh.workflowservice.workflow.ShipmentWorkflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * shipment.events akışındaki READY_FOR_DISPATCH geçişlerini ilgili ShipmentWorkflow'a sinyal olarak iletir.
 * Sinyal gönderi sürümüyle gider; yeniden teslim edilen olay workflow tarafında yok sayılır.
 */
@Component
@ConditionalOnProperty(name = "routemesh.dispatch.consumer-enabled", havingValue = "true")
public class ReadyForDispatchRelay {

    static final String STATUS_CHANGED = "ShipmentStatusChanged";
    static final String READY_FOR_DISPATCH = "READY_FOR_DISPATCH";

    private static final Logger log = LoggerFactory.getLogger(ReadyForDispatchRelay.class);

    private final WorkflowClient client;
    private final JsonMapper jsonMapper;

    public ReadyForDispatchRelay(WorkflowClient client, JsonMapper jsonMapper) {
        this.client = client;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = "shipment.events")
    public void onMessage(String message) {
        JsonNode envelope = jsonMapper.readTree(message);
        if (!STATUS_CHANGED.equals(envelope.path("eventType").asString())) {
            return;
        }
        JsonNode data = envelope.path("data");
        if (!READY_FOR_DISPATCH.equals(data.path("toStatus").asString())) {
            return;
        }
        UUID shipmentId = UUID.fromString(data.path("shipmentId").asString());
        long version = data.path("version").asLong();
        try {
            client.newWorkflowStub(ShipmentWorkflow.class, "shipment-" + shipmentId).readyForDispatch(version);
            log.info("Signalled readyForDispatch for shipment {} (version {})", shipmentId, version);
        } catch (WorkflowNotFoundException e) {
            // iş akışı yok ya da bitmiş (örn. workflow dışında oluşturulmuş ya da iptal edilmiş gönderi)
            log.info("No open workflow for shipment {}, ignoring readyForDispatch", shipmentId);
        }
    }
}
