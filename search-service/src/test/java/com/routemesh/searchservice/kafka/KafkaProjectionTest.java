package com.routemesh.searchservice.kafka;

import static com.routemesh.searchservice.Events.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.routemesh.searchservice.Events;
import com.routemesh.searchservice.TestcontainersConfiguration;
import com.routemesh.searchservice.projection.ShipmentViewRepository;

@Import({TestcontainersConfiguration.class, KafkaProjectionTest.KafkaConfig.class})
@SpringBootTest(properties = {
        "routemesh.search.retry-attempts=3",
        "routemesh.search.retry-initial-interval=100ms",
        "routemesh.search.retry-max-interval=200ms",
        "spring.kafka.consumer.properties.metadata.max.age.ms=1000"})
class KafkaProjectionTest {

    private static final String TOPIC = "shipment.events";
    private static final String DLT = "shipment.events.search-service.dlt";

    static class KafkaConfig {
        @Bean
        @ServiceConnection
        @SuppressWarnings("resource")
        KafkaContainer kafka() {
            return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
        }
    }

    @Autowired
    private KafkaTemplate<String, String> producer;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JdbcClient jdbc;

    @MockitoSpyBean
    private ShipmentViewRepository view;

    private final UUID shipment = UUID.randomUUID();
    private final UUID courier = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reset(view);
    }

    @Test
    void eventsArrivingOutOfOrderAndTwiceProjectToTheSameFinalState() {
        String created = Events.created(UUID.randomUUID(), shipment, T0);
        String assigned = Events.statusChanged(UUID.randomUUID(), shipment, "ASSIGNED", courier, 1, T0.plusSeconds(1));
        String delivering = Events.statusChanged(UUID.randomUUID(), shipment, "OUT_FOR_DELIVERY", courier, 2,
                T0.plusSeconds(2));

        for (String event : List.of(delivering, assigned, assigned, created, delivering)) {
            producer.send(TOPIC, shipment.toString(), event);
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = jdbc.sql("SELECT status, version, merchant_id, courier_id FROM shipment_view "
                    + "WHERE id = :id").param("id", shipment).query().singleRow();
            assertThat(row).containsEntry("status", "OUT_FOR_DELIVERY").containsEntry("version", 2L)
                    .containsEntry("courier_id", courier);
            assertThat(row.get("merchant_id")).isEqualTo(Events.MERCHANT);
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT count(*) FROM processed_events").query(Long.class).single()).isGreaterThanOrEqualTo(3));
    }

    @Test
    void aMalformedRecordGoesStraightToTheDeadLetterTopicWithoutTouchingTheView() {
        String garbage = "{not json " + shipment;

        producer.send(TOPIC, shipment.toString(), garbage);

        ConsumerRecord<String, String> dead = awaitDeadLetter(garbage);
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM shipment_view WHERE id = :id").param("id", shipment)
                .query(Long.class).single()).isZero();
    }

    @Test
    void aTransientDatabaseFailureIsRetriedAndThenProjected() {
        doThrow(new TransientDataAccessResourceException("db hiccup"))
                .doThrow(new TransientDataAccessResourceException("db hiccup"))
                .doCallRealMethod()
                .when(view).applyStatusChanged(any(), anyString(), any(), anyLong(), any());

        producer.send(TOPIC, shipment.toString(),
                Events.statusChanged(UUID.randomUUID(), shipment, "ASSIGNED", courier, 1, T0));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT count(*) FROM shipment_view WHERE id = :id AND version = 1").param("id", shipment)
                        .query(Long.class).single()).isEqualTo(1));
        verify(view, times(3)).applyStatusChanged(any(), anyString(), any(), anyLong(), any());
    }

    @Test
    void aRecordThatKeepsFailingIsDeadLetteredAfterTheRetryBudgetAndTheNextOneStillProjects() {
        doThrow(new TransientDataAccessResourceException("db down"))
                .when(view).applyStatusChanged(any(), anyString(), any(), anyLong(), any());
        String poison = Events.statusChanged(UUID.randomUUID(), shipment, "ASSIGNED", courier, 1, T0);
        producer.send(TOPIC, shipment.toString(), poison);

        awaitDeadLetter(poison);
        verify(view, times(4)).applyStatusChanged(any(), anyString(), any(), anyLong(), any());

        reset(view);
        doCallRealMethod().when(view).applyCreated(any(), any(), anyString(), anyLong(), anyString(), any());
        UUID other = UUID.randomUUID();
        producer.send(TOPIC, other.toString(), Events.created(UUID.randomUUID(), other, T0));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT count(*) FROM shipment_view WHERE id = :id").param("id", other)
                        .query(Long.class).single()).isEqualTo(1));
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
