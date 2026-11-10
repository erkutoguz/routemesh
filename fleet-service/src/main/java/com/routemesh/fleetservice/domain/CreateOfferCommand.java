package com.routemesh.fleetservice.domain;

import java.util.UUID;

public record CreateOfferCommand(
        UUID offerId,
        UUID shipmentId,
        UUID courierId,
        long weightGrams,
        int ttlSeconds) {
}
