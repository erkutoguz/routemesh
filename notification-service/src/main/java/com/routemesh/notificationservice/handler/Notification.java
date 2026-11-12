package com.routemesh.notificationservice.handler;

import java.util.Map;
import java.util.UUID;

public record Notification(
        UUID eventId,
        String template,
        RecipientType recipientType,
        UUID recipientId,
        Channel channel,
        UUID shipmentId,
        Map<String, Object> params) {
}
