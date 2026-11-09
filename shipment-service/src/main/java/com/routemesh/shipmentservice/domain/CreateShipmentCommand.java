package com.routemesh.shipmentservice.domain;

import java.util.UUID;

public record CreateShipmentCommand(
        String idempotencyKey,
        UUID merchantId,
        String recipientName,
        String recipientPhone,
        String deliveryAddress,
        double deliveryLatitude,
        double deliveryLongitude,
        UUID quoteId,
        long finalAmountMinor,
        long basePriceMinor,
        String currency,
        UUID shipmentId) {

    public CreateShipmentCommand(String idempotencyKey, UUID merchantId, String recipientName,
            String recipientPhone, String deliveryAddress, double deliveryLatitude, double deliveryLongitude,
            UUID quoteId, long finalAmountMinor, long basePriceMinor, String currency) {
        this(idempotencyKey, merchantId, recipientName, recipientPhone, deliveryAddress, deliveryLatitude,
                deliveryLongitude, quoteId, finalAmountMinor, basePriceMinor, currency, null);
    }
}
