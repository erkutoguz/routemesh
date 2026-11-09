package com.routemesh.shipmentservice.domain;

import java.time.Instant;
import java.util.UUID;

public record HistoryEntry(
        String fromStatus,
        String toStatus,
        String actor,
        UUID courierId,
        String reason,
        long version,
        Instant createdAt) {
}
