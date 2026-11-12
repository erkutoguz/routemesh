package com.routemesh.notificationservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.notificationservice.TestcontainersConfiguration;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

/**
 * Kafka'da izleme: üretici bağlamı {@code traceparent} başlığıyla taşır; Debezium başlığı olan ({@code eventId})
 * ama bağlamı olmayan bir kayıt tüketicide yeni bir iz başlatır ve olay kimliğini span özniteliği olarak yazar.
 */
@SpringBootTest(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.properties.metadata.max.age.ms=1000",
        "management.tracing.export.enabled=true",
        "management.tracing.export.otlp.enabled=false",
        "management.opentelemetry.tracing.export.schedule-delay=50ms"})
@Import({TestcontainersConfiguration.class, KafkaTracingTest.Infrastructure.class})
class KafkaTracingTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean
        @ServiceConnection
        @SuppressWarnings("resource")
        KafkaContainer kafka() {
            return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
        }

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    private KafkaTemplate<String, String> producer;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private InMemorySpanExporter spans;

    @Autowired
    private Tracer tracer;

    @MockitoBean
    private ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments;

    @Test
    void theProducerCarriesTheTraceContextInATraceparentHeader() {
        Span parent = tracer.nextSpan().name("produce-parent").start();
        String traceId;
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            traceId = parent.context().traceId();
            producer.send("fleet.events", UUID.randomUUID().toString(), irrelevantEvent()).join();
        } finally {
            parent.end();
        }

        ConsumerRecord<String, String> record = awaitRecord("fleet.events", r -> r.headers().lastHeader("traceparent") != null);
        String header = new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
        assertThat(header).contains(traceId);
    }

    @Test
    void aDebeziumRecordStartsANewTraceInTheListenerAndCarriesItsEventIdAsAnAttribute() {
        UUID eventId = UUID.randomUUID();
        ProducerRecord<String, String> record = new ProducerRecord<>("shipment.events", UUID.randomUUID().toString(),
                irrelevantEvent());
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        try (KafkaProducer<String, String> debezium = new KafkaProducer<>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            debezium.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(spans.getFinishedSpanItems())
                .anySatisfy(span -> {
                    assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
                    assertThat(span.getAttributes().asMap().entrySet()).anySatisfy(attribute -> {
                        assertThat(attribute.getKey().getKey()).isEqualTo("routemesh.event.id");
                        assertThat(attribute.getValue()).isEqualTo(eventId.toString());
                    });
                    assertThat(span.getParentSpanId()).isEqualTo("0000000000000000");
                }));
    }

    private static String irrelevantEvent() {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"SomethingElse\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2030-01-01T00:00:00Z\",\"aggregateId\":\"x\",\"data\":{}}";
    }

    private ConsumerRecord<String, String> awaitRecord(String topic,
            java.util.function.Predicate<ConsumerRecord<String, String>> filter) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "trace-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (filter.test(r)) {
                        found.add(r);
                    }
                });
                return !found.isEmpty();
            });
        }
        return found.get(0);
    }
}
