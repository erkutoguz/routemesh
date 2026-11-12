package com.routemesh.workflowservice.kafka;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.routemesh.contracts.fleet.FleetServiceGrpc;
import com.routemesh.contracts.fleet.ReleaseCourierByShipmentRequest;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * shipment.events akışında gönderi terminal duruma ({@code DELIVERED}, {@code CANCELLED}, {@code LOST}) geçtiğinde onu
 * tutan kuryeyi filo servisinde serbest bırakır (kurye {@code IDLE} olur, yeni teklif alabilir).
 *
 * <p>Kurye kimliği olaydan okunmaz: {@code ASSIGNED -> CANCELLED} geçişinde gönderideki kurye temizlendiği için olay
 * kurye taşımaz. Filo, gönderiyi tutan kuryeyi kendi kaydından bulur; tutan yoksa çağrı etkisizdir, bu yüzden
 * yeniden teslim edilen olay ya da hiç kurye atanmamış bir gönderi zararsızdır. {@code DELIVERY_FAILED} ve
 * {@code RETURN_TO_WAREHOUSE} terminal değildir (kurye gönderiyi depoya dönene kadar taşır).
 *
 * <p>Ayrı tüketici grubu şart: aynı gruptaki dinleyiciler bölümleri aralarında bölüşür.
 */
@Component
@ConditionalOnProperty(name = "routemesh.courier-release.consumer-enabled", havingValue = "true")
public class CourierReleaseRelay {

    static final String STATUS_CHANGED = "ShipmentStatusChanged";
    static final Set<String> TERMINAL = Set.of("DELIVERED", "CANCELLED", "LOST");

    private static final Logger log = LoggerFactory.getLogger(CourierReleaseRelay.class);

    private final FleetServiceGrpc.FleetServiceBlockingStub fleet;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meters;

    public CourierReleaseRelay(FleetServiceGrpc.FleetServiceBlockingStub fleet, JsonMapper jsonMapper,
                               MeterRegistry meters) {
        this.fleet = fleet;
        this.jsonMapper = jsonMapper;
        this.meters = meters;
    }

    @KafkaListener(topics = "shipment.events", groupId = "workflow-service-courier-release")
    public void onMessage(String message) {
        JsonNode envelope = jsonMapper.readTree(message);
        if (!STATUS_CHANGED.equals(envelope.path("eventType").asString())) {
            return;
        }
        JsonNode data = envelope.path("data");
        String to = data.path("toStatus").asString();
        if (!TERMINAL.contains(to)) {
            return;
        }
        UUID shipmentId = UUID.fromString(data.path("shipmentId").asString());
        // Geçici fleet hatası fırlar: Kafka olayı yeniden teslim eder (KafkaConsumerConfiguration)
        boolean released = fleet.releaseCourierByShipment(ReleaseCourierByShipmentRequest.newBuilder()
                .setShipmentId(shipmentId.toString()).build()).getReleased();
        // outcome yalnızca released | not_held
        meters.counter("routemesh.workflow.courier.releases", "outcome", released ? "released" : "not_held")
                .increment();
        log.info("Courier of shipment {} ({}): {}", shipmentId, to, released ? "released" : "none held");
    }
}
