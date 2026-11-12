package com.routemesh.locationservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.routemesh.locationservice.config.TrackingProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@SuppressWarnings("unchecked")
class TelemetryPublisherTest {

    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private TelemetryPublisher publisher;

    private TelemetryPublisher publisher(boolean enabled, int capacity) {
        publisher = new TelemetryPublisher(kafka, JsonMapper.builder().build(),
                new TrackingProperties(100, 41.0, 29.0, Duration.ofHours(48), Duration.ofHours(24),
                        "location.events", "courier.locations", enabled, capacity),
                meters);
        return publisher;
    }

    private double dropped() {
        return meters.get("routemesh.location.telemetry.dropped").counter().count();
    }

    @AfterEach
    void stop() {
        if (publisher != null) {
            publisher.stop();
        }
    }

    @Test
    void acceptedPositionsAreSentToTheTelemetryTopicKeyedByCourier() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        TelemetryPublisher p = publisher(true, 10);
        p.start();

        p.offer("c1", 2, 7, 41.0, 29.0);

        verify(kafka, timeout(3000)).send(eq("courier.locations"), eq("c1"), org.mockito.ArgumentMatchers.contains("\"sequenceNumber\":7"));
        assertThat(dropped()).isZero();
    }

    @Test
    void fullQueue_dropsInsteadOfBlocking() {
        TelemetryPublisher p = publisher(true, 2); // thread başlatılmadı, kuyruk boşalmaz

        p.offer("c1", 1, 1, 41.0, 29.0);
        p.offer("c1", 1, 2, 41.0, 29.0);
        p.offer("c1", 1, 3, 41.0, 29.0);

        assertThat(dropped()).isEqualTo(1);
    }

    @Test
    void kafkaFailure_countsAsDroppedAndNeverThrows() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenThrow(new IllegalStateException("buffer full"));
        TelemetryPublisher p = publisher(true, 10);
        p.start();

        p.offer("c1", 1, 1, 41.0, 29.0);
        p.offer("c1", 1, 2, 41.0, 29.0);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(dropped()).isEqualTo(2));
    }

    @Test
    void disabled_publishesNothing() {
        TelemetryPublisher p = publisher(false, 10);
        p.start();

        p.offer("c1", 1, 1, 41.0, 29.0);

        verifyNoInteractions(kafka);
    }
}
