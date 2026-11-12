package com.routemesh.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

class EventIdObservationFilterTest {

    private final EventIdObservationFilter filter = new EventIdObservationFilter();

    @Test
    void theDebeziumEventIdHeaderBecomesASpanAttribute() {
        UUID eventId = UUID.randomUUID();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("shipment.events", 0, 5L, "key", "value");
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));

        Observation.Context context = filter.map(receiverContext(record));

        assertThat(context.getHighCardinalityKeyValue(EventIdObservationFilter.KEY).getValue())
                .isEqualTo(eventId.toString());
    }

    @Test
    void theLastHeaderWinsWhenARecordWasRedelivered() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        record.headers().add("eventId", "first".getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", "second".getBytes(StandardCharsets.UTF_8));

        Observation.Context context = filter.map(receiverContext(record));

        assertThat(context.getHighCardinalityKeyValue(EventIdObservationFilter.KEY).getValue()).isEqualTo("second");
    }

    @Test
    void recordsWithoutTheHeaderAndOtherObservationsAreLeftAlone() {
        ConsumerRecord<String, String> noHeader = new ConsumerRecord<>("location.events", 0, 0L, "k", "v");
        ConsumerRecord<String, String> nullValue = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        nullValue.headers().add("eventId", null);

        assertThat(filter.map(receiverContext(noHeader)).getHighCardinalityKeyValue(EventIdObservationFilter.KEY))
                .isNull();
        assertThat(filter.map(receiverContext(nullValue)).getHighCardinalityKeyValue(EventIdObservationFilter.KEY))
                .isNull();
        Observation.Context other = new Observation.Context();
        assertThat(filter.map(other)).isSameAs(other);
        assertThat(other.getHighCardinalityKeyValue(EventIdObservationFilter.KEY)).isNull();
    }

    @Test
    void theAutoConfigurationRegistersTheFilterWhenSpringKafkaIsPresent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformObservabilityAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(ObservationFilter.class)
                        .hasSingleBean(EventIdObservationFilter.class));
    }

    private static KafkaRecordReceiverContext receiverContext(ConsumerRecord<String, String> record) {
        return new KafkaRecordReceiverContext(record, "listener", "client", "group", () -> "cluster");
    }
}
