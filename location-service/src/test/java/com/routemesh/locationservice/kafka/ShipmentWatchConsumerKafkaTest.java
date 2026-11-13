package com.routemesh.locationservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.routemesh.contracts.common.LatLong;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.ShipmentStatus;
import com.routemesh.locationservice.config.TrackingProperties;
import com.routemesh.locationservice.store.CourierLocationStore;
import com.routemesh.locationservice.store.Watch;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gerçek Kafka ile: geçici hata yeniden denenip başarır; bozuk ya da bütçeyi aşan kayıt sessizce düşmez, DLT'ye yazılır
 * ve sayaç artar.
 */
@SpringBootTest(classes = {KafkaConsumerConfiguration.class, ShipmentWatchConsumer.class,
        ShipmentWatchConsumerKafkaTest.Beans.class}, properties = {
        "routemesh.tracking.consumer-enabled=true",
        "routemesh.kafka.retry-initial-interval=100ms",
        "routemesh.kafka.retry-max-interval=200ms",
        "routemesh.kafka.retry-max-elapsed=1s",
        "spring.kafka.consumer.group-id=location-service",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.properties.metadata.max.age.ms=1000"})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, ServiceConnectionAutoConfiguration.class})
class ShipmentWatchConsumerKafkaTest {

    private static final String DLT = "shipment.events.location-service.dlt";

    static class Beans {
        @Bean
        @ServiceConnection
        @SuppressWarnings("resource")
        KafkaContainer kafka() {
            return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        TrackingProperties trackingProperties() {
            return new TrackingProperties(100, 41.0082, 28.9784, Duration.ofHours(48), Duration.ofHours(24),
                    "location.events", "courier.locations", true, 10);
        }
    }

    @Autowired
    private KafkaTemplate<String, String> producer;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private MeterRegistry meters;

    @MockitoBean
    private ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments;

    @MockitoBean
    private CourierLocationStore store;

    private final UUID shipmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reset(shipments, store);
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceeds_withoutDeadLettering() {
        double before = dltCount();
        when(shipments.getShipment(any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .thenReturn(assigned());
        when(store.armWatch(any())).thenReturn(true);

        producer.send("shipment.events", shipmentId.toString(), assignedEvent());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> verify(store).armWatch(any(Watch.class)));
        verify(shipments, times(3)).getShipment(any());
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(dltCount()).isEqualTo(before));
    }

    @Test
    void aMalformedRecordGoesStraightToTheDeadLetterTopic_andIsCounted() {
        double before = dltCount();
        String garbage = "{not json " + shipmentId;

        producer.send("shipment.events", shipmentId.toString(), garbage);

        ConsumerRecord<String, String> dead = awaitDeadLetter(garbage);
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(dltCount()).isGreaterThan(before));
    }

    @Test
    void aRecordThatKeepsFailingIsDeadLetteredAfterTheRetryBudget_notDropped() {
        double before = dltCount();
        when(shipments.getShipment(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
        String event = assignedEvent();

        producer.send("shipment.events", shipmentId.toString(), event);

        awaitDeadLetter(event);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(dltCount()).isGreaterThan(before));
        verify(shipments, atLeast(2)).getShipment(any());
        verify(store, never()).armWatch(any());
    }

    private double dltCount() {
        var counter = meters.find("routemesh.location.dlt.messages").tag("topic", "shipment.events").counter();
        return counter == null ? 0 : counter.count();
    }

    private Shipment assigned() {
        return Shipment.newBuilder().setId(shipmentId.toString()).setMerchantId(UUID.randomUUID().toString())
                .setStatus(ShipmentStatus.SHIPMENT_STATUS_ASSIGNED).setCourierId(UUID.randomUUID().toString())
                .setDeliveryLocation(LatLong.newBuilder().setLatitude(41.05).setLongitude(29.02)).build();
    }

    private String assignedEvent() {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"ShipmentStatusChanged\",\"data\":"
                + "{\"shipmentId\":\"" + shipmentId + "\",\"toStatus\":\"ASSIGNED\",\"version\":3}}";
    }

    private ConsumerRecord<String, String> awaitDeadLetter(String value) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(DLT));
            await().atMost(Duration.ofSeconds(45)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (value.equals(r.value())) {
                        found.add(r);
                    }
                });
                return !found.isEmpty();
            });
        }
        return found.get(0);
    }
}
