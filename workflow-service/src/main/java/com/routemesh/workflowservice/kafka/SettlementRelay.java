package com.routemesh.workflowservice.kafka;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.routemesh.workflowservice.config.TemporalProperties;
import com.routemesh.workflowservice.settlement.SettlementWorkflow;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * shipment.events akışındaki DELIVERED geçişinde {@code settlement-<gönderi>} iş akışını başlatır. Yeniden teslim edilen
 * olay aynı kimliğe düşer ve yok sayılır; başarısız bitmiş iş akışı yeniden başlatılabilir.
 *
 * <p>Ayrı tüketici grubu şart: aynı gruptaki iki dinleyici bölümleri aralarında bölüşür ve her biri olayların
 * yalnızca bir kısmını görürdü.
 */
@Component
@ConditionalOnProperty(name = "routemesh.settlement.consumer-enabled", havingValue = "true")
public class SettlementRelay {

    static final String STATUS_CHANGED = "ShipmentStatusChanged";
    static final String DELIVERED = "DELIVERED";

    private static final Logger log = LoggerFactory.getLogger(SettlementRelay.class);

    private final WorkflowClient client;
    private final TemporalProperties properties;
    private final JsonMapper jsonMapper;

    public SettlementRelay(WorkflowClient client, TemporalProperties properties, JsonMapper jsonMapper) {
        this.client = client;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = "shipment.events", groupId = "workflow-service-settlement")
    public void onMessage(String message) {
        JsonNode envelope = jsonMapper.readTree(message);
        if (!STATUS_CHANGED.equals(envelope.path("eventType").asString())) {
            return;
        }
        JsonNode data = envelope.path("data");
        if (!DELIVERED.equals(data.path("toStatus").asString())) {
            return;
        }
        UUID shipmentId = UUID.fromString(data.path("shipmentId").asString());
        SettlementWorkflow workflow = client.newWorkflowStub(SettlementWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(properties.taskQueue())
                .setWorkflowId("settlement-" + shipmentId)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .build());
        try {
            WorkflowClient.start(workflow::run, shipmentId);
            log.info("Started settlement for delivered shipment {}", shipmentId);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("Settlement for shipment {} already exists, ignoring", shipmentId);
        }
    }
}
