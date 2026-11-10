package com.routemesh.locationservice.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.routemesh.contracts.events.ApproachingDestinationEvent;
import com.routemesh.contracts.events.EventEnvelope;
import com.routemesh.locationservice.config.TrackingProperties;
import com.routemesh.locationservice.store.CourierLocationStore;
import com.routemesh.locationservice.store.ProximityHit;

import tools.jackson.databind.json.JsonMapper;

/**
 * Yaklaşma olayını doğrudan Kafka'ya yazar (bu serviste ilişkisel veritabanı ve outbox yok).
 * Yazım başarısız olursa Redis'teki tetik geri alınır; kurye bir sonraki konum paketinde yeniden tetikler.
 * Garanti "en az bir kez"dir, olay kimliği gönderi ve türden türetildiği için tüketici tekilleştirebilir.
 */
@Component
public class ApproachPublisher {

    private static final Logger log = LoggerFactory.getLogger(ApproachPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper jsonMapper;
    private final TrackingProperties properties;
    private final CourierLocationStore store;

    public ApproachPublisher(KafkaTemplate<String, String> kafka, JsonMapper jsonMapper,
                             TrackingProperties properties, CourierLocationStore store) {
        this.kafka = kafka;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.store = store;
    }

    public static UUID eventId(String shipmentId, String kind) {
        return UUID.nameUUIDFromBytes(("approach:" + shipmentId + ":" + kind).getBytes(StandardCharsets.UTF_8));
    }

    public void publish(ProximityHit hit, double latitude, double longitude) {
        try {
            ApproachingDestinationEvent data = new ApproachingDestinationEvent(UUID.fromString(hit.shipmentId()),
                    UUID.fromString(hit.courierId()), hit.kind(), hit.distanceMeters(), latitude, longitude);
            String payload = jsonMapper.writeValueAsString(new EventEnvelope<>(eventId(hit.shipmentId(), hit.kind()),
                    ApproachingDestinationEvent.TYPE, ApproachingDestinationEvent.VERSION, Instant.now(),
                    hit.shipmentId(), data));
            kafka.send(properties.approachTopic(), hit.shipmentId(), payload).whenComplete((result, error) -> {
                if (error != null) {
                    rearm(hit, error);
                }
            });
        } catch (RuntimeException e) {
            rearm(hit, e);
        }
    }

    private void rearm(ProximityHit hit, Throwable error) {
        log.warn("Approach event for shipment {} ({}) could not be published, re-arming: {}",
                hit.shipmentId(), hit.kind(), error.toString());
        try {
            store.rearm(hit);
        } catch (RuntimeException e) {
            log.error("Could not re-arm watch for shipment {}", hit.shipmentId(), e);
        }
    }
}
