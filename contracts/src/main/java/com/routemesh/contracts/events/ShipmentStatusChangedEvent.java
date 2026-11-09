package com.routemesh.contracts.events;

import java.util.UUID;

public record ShipmentStatusChangedEvent(
        UUID shipmentId,
        String fromStatus,
        String toStatus,
        String actor,
        UUID courierId,
        String reason,
        long version) {

    public static final String TYPE = "ShipmentStatusChanged";
    public static final int VERSION = 1;
}
