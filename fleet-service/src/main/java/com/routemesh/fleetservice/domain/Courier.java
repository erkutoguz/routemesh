package com.routemesh.fleetservice.domain;

import java.time.Instant;
import java.util.UUID;

public record Courier(
        UUID courierId,
        CourierStatus status,
        int maxWeightGrams,
        UUID activeShipmentId,
        long fenceToken,
        Instant updatedAt) {
}
