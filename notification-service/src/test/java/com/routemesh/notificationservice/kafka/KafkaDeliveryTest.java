package com.routemesh.notificationservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.IssueDeliveryOtpRequest;
import com.routemesh.contracts.shipment.IssueDeliveryOtpResponse;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.notificationservice.Events;
import com.routemesh.notificationservice.TestcontainersConfiguration;
import com.routemesh.notificationservice.sms.SmsChannel;

import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.grpc.StatusRuntimeException;

@Import({TestcontainersConfiguration.class, KafkaDeliveryTest.KafkaConfig.class})
@SpringBootTest(properties = {
        "routemesh.notification.retry-attempts=3",
        "routemesh.notification.retry-initial-interval=100ms",
        "routemesh.notification.retry-max-interval=200ms",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.properties.metadata.max.age.ms=1000"})
class KafkaDeliveryTest {

    private static final String DLT = "shipment.events.notification-service.dlt";

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

    @Autowired
    private MeterRegistry meters;

    @MockitoBean
    private ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments;

    @MockitoSpyBean
    private SmsChannel sms;

    private final UUID shipmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reset(sms);
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(Shipment.newBuilder()
                .setId(shipmentId.toString()).setMerchantId(UUID.randomUUID().toString())
                .setRecipientPhone("+905551112233").build());
        when(shipments.issueDeliveryOtp(any(IssueDeliveryOtpRequest.class)))
                .thenReturn(IssueDeliveryOtpResponse.newBuilder().setCode("482916").build());
    }

    @Test
    void aDuplicateKafkaRecordSendsOneSms() {
        String event = Events.statusChanged(UUID.randomUUID(), shipmentId, "ASSIGNED", "OUT_FOR_DELIVERY");

        producer.send("shipment.events", shipmentId.toString(), event);
        producer.send("shipment.events", shipmentId.toString(), event);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> verify(sms).send(anyString(), anyString()));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE shipment_id = :s")
                        .param("s", shipmentId).query(Long.class).single()).isEqualTo(1));
    }

    @Test
    void aMalformedRecordGoesStraightToTheDeadLetterTopic() {
        String garbage = "{not json " + shipmentId;

        producer.send("shipment.events", shipmentId.toString(), garbage);

        ConsumerRecord<String, String> dead = awaitDeadLetter(garbage);
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                meters.get("routemesh.notification.dlt.messages").tag("topic", "shipment.events").counter().count())
                .isGreaterThanOrEqualTo(1));
    }

    @Test
    void aPermanentlyFailingRecordIsDeadLetteredWithoutRetries() {
        when(shipments.getShipment(any(GetShipmentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));
        String event = Events.statusChanged(UUID.randomUUID(), shipmentId, "OUT_FOR_DELIVERY", "DELIVERED");

        producer.send("shipment.events", shipmentId.toString(), event);

        awaitDeadLetter(event);
        verify(shipments, times(1)).getShipment(any());
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE shipment_id = :s").param("s", shipmentId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceeds() {
        when(shipments.getShipment(any(GetShipmentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .thenReturn(Shipment.newBuilder().setId(shipmentId.toString())
                        .setMerchantId(UUID.randomUUID().toString()).setRecipientPhone("+905551112233").build());

        producer.send("shipment.events", shipmentId.toString(),
                Events.statusChanged(UUID.randomUUID(), shipmentId, "ASSIGNED", "OUT_FOR_DELIVERY"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> verify(sms).send(anyString(), anyString()));
        verify(shipments, times(3)).getShipment(any());
    }

    @Test
    void aRecordThatKeepsFailingIsDeadLetteredAfterTheRetryBudget() {
        when(shipments.getShipment(any(GetShipmentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
        String event = Events.statusChanged(UUID.randomUUID(), shipmentId, "OUT_FOR_DELIVERY", "DELIVERED");

        producer.send("shipment.events", shipmentId.toString(), event);

        awaitDeadLetter(event);
        verify(shipments, times(4)).getShipment(any());
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
