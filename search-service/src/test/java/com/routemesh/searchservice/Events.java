package com.routemesh.searchservice;

import java.time.Instant;
import java.util.UUID;

public final class Events {

    public static final UUID MERCHANT = UUID.fromString("00000000-0000-4000-8000-000000000002");
    public static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");

    private Events() {
    }

    public static String created(UUID eventId, UUID shipmentId, Instant at) {
        return envelope(eventId, "ShipmentCreated", at, """
                {"shipmentId":"%s","merchantId":"%s","quoteId":"%s","finalAmountMinor":7500,"basePriceMinor":7500,
                "currency":"TRY","status":"PENDING_PAYMENT","deliveryLatitude":40.9,"deliveryLongitude":29.0}
                """.formatted(shipmentId, MERCHANT, UUID.randomUUID()));
    }

    public static String statusChanged(UUID eventId, UUID shipmentId, String to, UUID courierId, long version,
                                       Instant at) {
        return envelope(eventId, "ShipmentStatusChanged", at, """
                {"shipmentId":"%s","fromStatus":"X","toStatus":"%s","actor":"SYSTEM","courierId":%s,"reason":null,
                "version":%d}
                """.formatted(shipmentId, to, courierId == null ? "null" : "\"" + courierId + "\"", version));
    }

    public static String envelope(UUID eventId, String type, Instant at, String data) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s","aggregateId":"x","data":%s}
                """.formatted(eventId, type, at, data);
    }
}
