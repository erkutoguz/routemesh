package com.routemesh.pricingservice.domain;

import java.util.UUID;

public record CreateQuoteCommand(
        String idempotencyKey,
        UUID merchantId,
        double originLatitude,
        double originLongitude,
        double destinationLatitude,
        double destinationLongitude,
        ServiceLevel serviceLevel,
        int weightGrams) {
}
