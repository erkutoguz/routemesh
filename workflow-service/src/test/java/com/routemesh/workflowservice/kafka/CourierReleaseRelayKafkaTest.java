package com.routemesh.workflowservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
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

import com.routemesh.contracts.fleet.FleetServiceGrpc;
import com.routemesh.contracts.fleet.ReleaseCourierResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gerçek Kafka ile: geçici hata yeniden denenip başarır; bozuk ya da bütçeyi aşan kayıt sessizce düşmez, DLT'ye yazılır
 * ve sayaç artar.
 */
@SpringBootTest(classes = {KafkaConsumerConfiguration.class, CourierReleaseRelay.class,
        CourierReleaseRelayKafkaTest.Beans.class}, properties = {
        "routemesh.courier-release.consumer-enabled=true",
        "routemesh.kafka.retry-initial-interval=100ms",
        "routemesh.kafka.retry-max-interval=200ms",
        "routemesh.kafka.retry-max-elapsed=1s",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.properties.metadata.max.age.ms=1000"})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, ServiceConnectionAutoConfiguration.class})
class CourierReleaseRelayKafkaTest {

    private static final String DLT = "shipment.events.workflow-service.dlt";

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
    }

    @Autowired
    private KafkaTemplate<String, String> producer;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private MeterRegistry meters;

    @MockitoBean
    private FleetServiceGrpc.FleetServiceBlockingStub fleet;

    private final UUID shipmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reset(fleet);
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceeds_withoutDeadLettering() {
        double before = dltCount();
        when(fleet.releaseCourierByShipment(any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .thenReturn(ReleaseCourierResponse.newBuilder().setReleased(true).build());

        producer.send("shipment.events", shipmentId.toString(), delivered());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> verify(fleet, times(3)).releaseCourierByShipment(any()));
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
        when(fleet.releaseCourierByShipment(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
        String event = delivered();

        producer.send("shipment.events", shipmentId.toString(), event);

        awaitDeadLetter(event);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(dltCount()).isGreaterThan(before));
        verify(fleet, atLeast(2)).releaseCourierByShipment(any());
    }

    private double dltCount() {
        var counter = meters.find("routemesh.workflow.dlt.messages").tag("topic", "shipment.events").counter();
        return counter == null ? 0 : counter.count();
    }

    private String delivered() {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"ShipmentStatusChanged\",\"data\":"
                + "{\"shipmentId\":\"" + shipmentId + "\",\"fromStatus\":\"OUT_FOR_DELIVERY\","
                + "\"toStatus\":\"DELIVERED\"}}";
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
