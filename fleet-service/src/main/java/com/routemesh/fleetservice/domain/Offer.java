package com.routemesh.fleetservice.domain;

import java.time.Instant;
import java.util.UUID;

public record Offer(
        UUID offerId,
        UUID shipmentId,
        UUID courierId,
        OfferStatus status,
        int weightGrams,
        long fenceToken,
        Instant expiresAt,
        Instant createdAt,
        Instant decidedAt) {
}
