package com.routemesh.contracts.events;

import java.time.Instant;
import java.util.UUID;

public record OfferStatusChangedEvent(
        UUID offerId,
        UUID shipmentId,
        UUID courierId,
        String fromStatus,
        String toStatus,
        Instant expiresAt) {

    public static final String TYPE = "OfferStatusChanged";
    public static final int VERSION = 1;
}
