package com.routemesh.locationservice.kafka;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.locationservice.config.TrackingProperties;
import com.routemesh.locationservice.store.CourierLocationStore;
import com.routemesh.locationservice.store.Watch;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * shipment.events akışından kurye yaklaşma izlemesini yönetir:
 * ASSIGNED → depo (PICKUP), OUT_FOR_DELIVERY → teslimat noktası (DELIVERY), bitiş durumları → izleme silinir.
 */
@Component
@ConditionalOnProperty(name = "routemesh.tracking.consumer-enabled", havingValue = "true")
public class ShipmentWatchConsumer {

    static final String STATUS_CHANGED = "ShipmentStatusChanged";
    static final String ASSIGNED = "ASSIGNED";
    static final String OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY";
    static final Set<String> ENDED = Set.of("DELIVERED", "DELIVERY_FAILED", "CANCELLED", "LOST",
            "RETURN_TO_WAREHOUSE", "ON_HOLD");

    private static final Logger log = LoggerFactory.getLogger(ShipmentWatchConsumer.class);

    private final CourierLocationStore store;
    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments;
    private final TrackingProperties properties;
    private final JsonMapper jsonMapper;

    public ShipmentWatchConsumer(CourierLocationStore store,
                                 ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments,
                                 TrackingProperties properties, JsonMapper jsonMapper) {
        this.store = store;
        this.shipments = shipments;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = "shipment.events")
    public void onMessage(String message) {
        JsonNode envelope = jsonMapper.readTree(message);
        if (!STATUS_CHANGED.equals(envelope.path("eventType").asString())) {
            return;
        }
        JsonNode data = envelope.path("data");
        String toStatus = data.path("toStatus").asString();
        UUID shipmentId = UUID.fromString(data.path("shipmentId").asString());

        if (ENDED.contains(toStatus)) {
            store.clearWatch(shipmentId.toString());
            log.info("Watch cleared for shipment {} ({})", shipmentId, toStatus);
        } else if (ASSIGNED.equals(toStatus) || OUT_FOR_DELIVERY.equals(toStatus)) {
            arm(shipmentId, toStatus, data);
        }
    }

    private void arm(UUID shipmentId, String toStatus, JsonNode data) {
        Shipment shipment;
        try {
            shipment = shipments.getShipment(GetShipmentRequest.newBuilder()
                    .setShipmentId(shipmentId.toString()).build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                log.warn("Shipment {} not found, ignoring {}", shipmentId, toStatus);
                return;
            }
            throw e; // geçici hata: Kafka aynı olayı yeniden teslim eder
        }
        // Tüketici gerideyse gönderi çoktan ilerlemiş olabilir; ilerlemiş duruma ait olay izlemeyi kurar
        if (!("SHIPMENT_STATUS_" + toStatus).equals(shipment.getStatus().name())) {
            log.info("Shipment {} is already {}, ignoring {}", shipmentId, shipment.getStatus(), toStatus);
            return;
        }
        String courierId = data.hasNonNull("courierId") ? data.path("courierId").asString()
                : shipment.hasCourierId() ? shipment.getCourierId() : null;
        if (courierId == null) {
            log.warn("Shipment {} has no courier, ignoring {}", shipmentId, toStatus);
            return;
        }
        courierId = UUID.fromString(courierId).toString();

        boolean pickup = ASSIGNED.equals(toStatus);
        if (!pickup && !shipment.hasDeliveryLocation()) {
            log.warn("Shipment {} has no delivery location, ignoring {}", shipmentId, toStatus);
            return;
        }
        double latitude = pickup ? properties.depotLatitude() : shipment.getDeliveryLocation().getLatitude();
        double longitude = pickup ? properties.depotLongitude() : shipment.getDeliveryLocation().getLongitude();

        boolean armed = store.armWatch(new Watch(shipmentId.toString(), courierId,
                pickup ? Watch.PICKUP : Watch.DELIVERY, latitude, longitude, shipment.getMerchantId(),
                data.path("version").asLong()));
        log.info("Watch {} for shipment {} courier {}", armed ? "armed" : "skipped", shipmentId, courierId);
    }
}
