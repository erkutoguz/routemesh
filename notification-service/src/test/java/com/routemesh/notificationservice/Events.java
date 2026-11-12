package com.routemesh.notificationservice;

import java.util.UUID;

public final class Events {

    private Events() {
    }

    public static String statusChanged(UUID eventId, UUID shipmentId, String from, String to) {
        return envelope(eventId, "ShipmentStatusChanged", """
                {"shipmentId":"%s","fromStatus":"%s","toStatus":"%s","actor":"test","courierId":null,"reason":null,"version":5}
                """.formatted(shipmentId, from, to));
    }

    public static String offerChanged(UUID eventId, UUID offerId, UUID shipmentId, UUID courierId, String from,
                                      String to) {
        return envelope(eventId, "OfferStatusChanged", """
                {"offerId":"%s","shipmentId":"%s","courierId":"%s","fromStatus":%s,"toStatus":"%s","expiresAt":"2030-01-01T00:00:00Z"}
                """.formatted(offerId, shipmentId, courierId, from == null ? "null" : "\"" + from + "\"", to));
    }

    public static String approaching(UUID eventId, UUID shipmentId, UUID courierId, String kind) {
        return envelope(eventId, "ApproachingDestination", """
                {"shipmentId":"%s","courierId":"%s","kind":"%s","distanceMeters":87.4,"latitude":41.0,"longitude":29.0}
                """.formatted(shipmentId, courierId, kind));
    }

    public static String envelope(UUID eventId, String type, String data) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2030-01-01T00:00:00Z","aggregateId":"x","data":%s}
                """.formatted(eventId, type, data);
    }
}
