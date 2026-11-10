package com.routemesh.locationservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.routemesh.locationservice.config.TrackingProperties;
import com.routemesh.locationservice.store.CourierLocationStore;
import com.routemesh.locationservice.store.ProximityHit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SuppressWarnings("unchecked")
class ApproachPublisherTest {

    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final CourierLocationStore store = mock(CourierLocationStore.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final TrackingProperties properties = new TrackingProperties(100, 41.0, 29.0, Duration.ofHours(48),
            Duration.ofHours(24), "location.events", "courier.locations", true, 10);
    private final ApproachPublisher publisher = new ApproachPublisher(kafka, json, properties, store);

    private final String shipmentId = UUID.randomUUID().toString();
    private final String courierId = UUID.randomUUID().toString();
    private final ProximityHit hit = new ProximityHit(courierId, shipmentId, "DELIVERY", 82.4);

    @Test
    void publishesTheEnvelopeKeyedByShipment() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(hit, 41.1, 29.2);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("location.events"), eq(shipmentId), payload.capture());
        JsonNode envelope = json.readTree(payload.getValue());
        assertThat(envelope.path("eventType").asString()).isEqualTo("ApproachingDestination");
        assertThat(envelope.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(envelope.path("aggregateId").asString()).isEqualTo(shipmentId);
        assertThat(envelope.path("eventId").asString())
                .isEqualTo(ApproachPublisher.eventId(shipmentId, "DELIVERY").toString());
        JsonNode data = envelope.path("data");
        assertThat(data.path("shipmentId").asString()).isEqualTo(shipmentId);
        assertThat(data.path("courierId").asString()).isEqualTo(courierId);
        assertThat(data.path("kind").asString()).isEqualTo("DELIVERY");
        assertThat(data.path("distanceMeters").asDouble()).isEqualTo(82.4);
        assertThat(data.path("latitude").asDouble()).isEqualTo(41.1);
        assertThat(data.path("longitude").asDouble()).isEqualTo(29.2);
        verify(store, never()).rearm(hit);
    }

    @Test
    void eventIdIsDeterministicPerShipmentAndKind() {
        assertThat(ApproachPublisher.eventId("s1", "DELIVERY")).isEqualTo(ApproachPublisher.eventId("s1", "DELIVERY"));
        assertThat(ApproachPublisher.eventId("s1", "DELIVERY")).isNotEqualTo(ApproachPublisher.eventId("s1", "PICKUP"));
    }

    @Test
    void asyncSendFailure_rearmsTheWatch() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publish(hit, 41.1, 29.2);

        verify(store).rearm(hit);
    }

    @Test
    void synchronousSendFailure_rearmsTheWatch() {
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("buffer full"));

        publisher.publish(hit, 41.1, 29.2);

        verify(store).rearm(hit);
    }
}
