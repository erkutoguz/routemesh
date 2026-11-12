package com.routemesh.notificationservice.handler;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.routemesh.contracts.shipment.Shipment;

import tools.jackson.databind.JsonNode;

@Component
public class ApproachHandler implements EventHandler {

    private final ShipmentDirectory directory;
    private final SmsNotifier sms;
    private final NotificationRepository notifications;

    public ApproachHandler(ShipmentDirectory directory, SmsNotifier sms, NotificationRepository notifications) {
        this.directory = directory;
        this.sms = sms;
        this.notifications = notifications;
    }

    @Override
    public String eventType() {
        return "ApproachingDestination";
    }

    @Override
    public boolean relevant(JsonNode data) {
        return "DELIVERY".equals(data.path("kind").asString());
    }

    @Override
    public void handle(UUID eventId, JsonNode data) {
        UUID shipmentId = UUID.fromString(data.path("shipmentId").asString());
        Shipment shipment = directory.get(shipmentId);
        notifications.insert(new Notification(eventId, "COURIER_APPROACHING", RecipientType.MERCHANT,
                UUID.fromString(shipment.getMerchantId()), Channel.IN_APP, shipmentId,
                Map.of("distanceMeters", Math.round(data.path("distanceMeters").asDouble()))));
        sms.toRecipient(eventId, "COURIER_APPROACHING_RECIPIENT", shipmentId, shipment.getRecipientPhone(),
                MessageTemplates.courierApproaching());
    }
}
