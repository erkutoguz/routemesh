package com.routemesh.platform.observability;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

/**
 * Debezium outbox olayları veritabanı satırından Kafka'ya geçerken izleme bağlamı taşımaz; tüketicide yeni bir iz
 * başlar. İzi olaya bağlamak için Debezium'un {@code eventId} başlığı span özniteliği olarak yazılır.
 */
public class EventIdObservationFilter implements ObservationFilter {

    public static final String HEADER = "eventId";
    public static final String KEY = "routemesh.event.id";

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context instanceof KafkaRecordReceiverContext receiver) {
            Header header = receiver.getRecord().headers().lastHeader(HEADER);
            if (header != null && header.value() != null) {
                context.addHighCardinalityKeyValue(KeyValue.of(KEY, new String(header.value(), StandardCharsets.UTF_8)));
            }
        }
        return context;
    }
}
