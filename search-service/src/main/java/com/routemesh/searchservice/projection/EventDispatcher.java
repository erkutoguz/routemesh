package com.routemesh.searchservice.projection;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.searchservice.inbox.InboxRepository;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventDispatcher {

    static final String CREATED = "ShipmentCreated";
    static final String STATUS_CHANGED = "ShipmentStatusChanged";

    private final ShipmentViewRepository view;
    private final InboxRepository inbox;
    private final TransactionTemplate tx;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meters;

    public EventDispatcher(ShipmentViewRepository view, InboxRepository inbox, TransactionTemplate tx,
                           JsonMapper jsonMapper, MeterRegistry meters) {
        this.meters = meters;
        this.view = view;
        this.inbox = inbox;
        this.tx = tx;
        this.jsonMapper = jsonMapper;
    }

    public void dispatch(String topic, String message) {
        JsonNode envelope = jsonMapper.readTree(message);
        String eventType = envelope.path("eventType").asString();
        if (!CREATED.equals(eventType) && !STATUS_CHANGED.equals(eventType)) {
            count("irrelevant");
            return;
        }
        UUID eventId = UUID.fromString(envelope.path("eventId").asString());
        Instant occurredAt = Instant.parse(envelope.path("occurredAt").asString());
        JsonNode data = envelope.path("data");
        tx.executeWithoutResult(status -> {
            if (!inbox.register(eventId, topic)) {
                count("duplicate");
                return;
            }
            if (CREATED.equals(eventType)) {
                view.applyCreated(uuid(data, "shipmentId"), uuid(data, "merchantId"),
                        required(data, "status"), data.path("finalAmountMinor").asLong(), required(data, "currency"),
                        occurredAt);
            } else {
                String courier = data.path("courierId").asString();
                long version = data.path("version").asLong();
                if (version < 1) {
                    throw new IllegalArgumentException("version must be positive");
                }
                view.applyStatusChanged(uuid(data, "shipmentId"), required(data, "toStatus"),
                        courier.isEmpty() ? null : UUID.fromString(courier), version, occurredAt);
            }
            count("applied");
        });
    }

    // outcome: applied | duplicate (gelen kutusunda var) | irrelevant (bu servisin ilgilenmediği olay türü)
    private void count(String outcome) {
        meters.counter("routemesh.search.projection.events", "outcome", outcome).increment();
    }

    private static UUID uuid(JsonNode data, String field) {
        return UUID.fromString(data.path(field).asString());
    }

    private static String required(JsonNode data, String field) {
        String value = data.path(field).asString();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value;
    }
}
