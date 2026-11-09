package com.routemesh.pricingservice.domain;

import java.time.Instant;
import java.util.UUID;

public record Quote(
        UUID id,
        UUID merchantId,
        double originLatitude,
        double originLongitude,
        double destinationLatitude,
        double destinationLongitude,
        String zoneCode,
        ServiceLevel serviceLevel,
        int weightGrams,
        long distanceMeters,
        long basePriceMinor,
        int surgeBps,
        long finalAmountMinor,
        String currency,
        int slaMinutes,
        QuoteStatus status,
        Instant createdAt,
        Instant expiresAt,
        UUID redeemedShipmentId,
        String redemptionKey) {
}
