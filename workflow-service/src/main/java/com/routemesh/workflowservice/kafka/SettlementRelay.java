package com.routemesh.workflowservice.kafka;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.routemesh.workflowservice.config.TemporalProperties;
import com.routemesh.workflowservice.settlement.SettlementInput;
import com.routemesh.workflowservice.settlement.SettlementKind;
import com.routemesh.workflowservice.settlement.SettlementWorkflow;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * shipment.events akışında gönderiyi sonlandıran geçişlerde {@code settlement-<gönderi>} iş akışını başlatır:
 * {@code DELIVERED} → teslimat, {@code RETURN_TO_WAREHOUSE → CANCELLED} → iade, {@code LOST} → kayıp, diğer
 * {@code CANCELLED} (hasar dahil) → teslimat öncesi iptal. {@code PENDING_PAYMENT → CANCELLED} atlanır: o zaman escrow
 * hiç dolmadı ya da {@code ShipmentWorkflow} telafisi zaten geri aldı. Yeniden teslim edilen olay aynı kimliğe düşer ve
 * yok sayılır; başarısız bitmiş iş akışı yeniden başlatılabilir.
 *
 * <p>Ayrı tüketici grubu şart: aynı gruptaki iki dinleyici bölümleri aralarında bölüşür ve her biri olayların
 * yalnızca bir kısmını görürdü.
 */
@Component
@ConditionalOnProperty(name = "routemesh.settlement.consumer-enabled", havingValue = "true")
public class SettlementRelay {

    static final String STATUS_CHANGED = "ShipmentStatusChanged";

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
        String to = data.path("toStatus").asString();
        String from = data.path("fromStatus").asString();
        UUID shipmentId = UUID.fromString(data.path("shipmentId").asString());
        SettlementKind kind = kindFor(from, to);
        if (kind == null) {
            return;
        }
        SettlementWorkflow workflow = client.newWorkflowStub(SettlementWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(properties.taskQueue())
                .setWorkflowId("settlement-" + shipmentId)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .build());
        try {
            WorkflowClient.start(workflow::run, new SettlementInput(shipmentId, kind));
            log.info("Started {} settlement for shipment {}", kind, shipmentId);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("Settlement for shipment {} already exists, ignoring", shipmentId);
        }
    }

    /**
     * @return para hareketi gerektirmeyen geçişte null
     * @throws IllegalArgumentException iptalin nereden geldiği bilinmiyorsa: yanlış şablonla para dağıtmaktansa olay
     *                                  yeniden denenmeden atlanır ve elle incelenir
     */
    static SettlementKind kindFor(String from, String to) {
        return switch (to) {
            case "DELIVERED" -> SettlementKind.DELIVERY;
            case "LOST" -> SettlementKind.LOST;
            case "CANCELLED" -> switch (from) {
                case "RETURN_TO_WAREHOUSE" -> SettlementKind.RETURN;
                case "PENDING_PAYMENT" -> null;
                case "PAID", "READY_FOR_DISPATCH", "ASSIGNED", "ON_HOLD", "DAMAGED" -> SettlementKind.CANCEL;
                default -> throw new IllegalArgumentException("cannot settle a cancellation from '" + from + "'");
            };
            default -> null;
        };
    }
}
