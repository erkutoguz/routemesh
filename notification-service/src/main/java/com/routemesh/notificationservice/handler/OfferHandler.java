package com.routemesh.notificationservice.handler;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

@Component
public class OfferHandler implements EventHandler {

    private final NotificationRepository notifications;

    public OfferHandler(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Override
    public String eventType() {
        return "OfferStatusChanged";
    }

    @Override
    public boolean relevant(JsonNode data) {
        return "OFFERED".equals(data.path("toStatus").asString());
    }

    @Override
    public void handle(UUID eventId, JsonNode data) {
        UUID courierId = UUID.fromString(data.path("courierId").asString());
        UUID shipmentId = UUID.fromString(data.path("shipmentId").asString());
        notifications.insert(new Notification(eventId, "NEW_OFFER", RecipientType.COURIER, courierId, Channel.IN_APP,
                shipmentId, Map.of("offerId", data.path("offerId").asString(),
                        "expiresAt", data.path("expiresAt").asString())));
    }
}
