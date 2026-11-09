package com.routemesh.shipmentservice.rest;

import java.time.Instant;
import java.util.UUID;

import com.routemesh.shipmentservice.domain.Shipment;

public record ShipmentResponse(
        UUID id,
        UUID merchantId,
        String status,
        UUID courierId,
        long version,
        String recipientName,
        String recipientPhone,
        String deliveryAddress,
        Location deliveryLocation,
        UUID quoteId,
        Amount finalAmount,
        Amount basePrice,
        Instant createdAt,
        Instant updatedAt) {

    public record Location(double latitude, double longitude) {
    }

    public record Amount(long amountMinor, String currency) {
    }

    static ShipmentResponse from(Shipment s) {
        return new ShipmentResponse(s.id(), s.merchantId(), s.status().name(), s.courierId(), s.version(),
                s.recipientName(), s.recipientPhone(), s.deliveryAddress(),
                new Location(s.deliveryLatitude(), s.deliveryLongitude()), s.quoteId(),
                new Amount(s.finalAmountMinor(), s.currency()), new Amount(s.basePriceMinor(), s.currency()),
                s.createdAt(), s.updatedAt());
    }
}
