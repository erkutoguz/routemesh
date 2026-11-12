package com.routemesh.contracts.events;

import java.util.UUID;

public record ShipmentCreatedEvent(
        UUID shipmentId,
        UUID merchantId,
        UUID quoteId,
        long finalAmountMinor,
        long basePriceMinor,
        String currency,
        String status,
        double deliveryLatitude,
        double deliveryLongitude) {

    public static final String TYPE = "ShipmentCreated";
    public static final int VERSION = 1;
}
