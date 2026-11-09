package com.routemesh.shipmentservice.domain;

import java.time.Instant;
import java.util.UUID;

public record Shipment(
        UUID id,
        UUID merchantId,
        ShipmentStatus status,
        UUID courierId,
        long version,
        String recipientName,
        String recipientPhone,
        String deliveryAddress,
        double deliveryLatitude,
        double deliveryLongitude,
        UUID quoteId,
        long finalAmountMinor,
        long basePriceMinor,
        String currency,
        Instant createdAt,
        Instant updatedAt) {
}
