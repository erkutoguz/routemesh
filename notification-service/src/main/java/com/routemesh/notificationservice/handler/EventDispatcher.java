package com.routemesh.notificationservice.handler;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.notificationservice.inbox.InboxRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventDispatcher {

    private final List<EventHandler> handlers;
    private final InboxRepository inbox;
    private final TransactionTemplate tx;
    private final JsonMapper jsonMapper;

    public EventDispatcher(List<EventHandler> handlers, InboxRepository inbox, TransactionTemplate tx,
                           JsonMapper jsonMapper) {
        this.handlers = handlers;
        this.inbox = inbox;
        this.tx = tx;
        this.jsonMapper = jsonMapper;
    }

    public void dispatch(String topic, String message) {
        JsonNode envelope = jsonMapper.readTree(message);
        String eventType = envelope.path("eventType").asString();
        JsonNode data = envelope.path("data");
        EventHandler handler = handlers.stream()
                .filter(h -> h.eventType().equals(eventType) && h.relevant(data))
                .findFirst().orElse(null);
        if (handler == null) {
            return;
        }
        UUID eventId = UUID.fromString(envelope.path("eventId").asString());
        tx.executeWithoutResult(status -> {
            if (inbox.register(eventId, topic)) {
                handler.handle(eventId, data);
            }
        });
    }
}
