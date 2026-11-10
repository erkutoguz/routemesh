package com.routemesh.contracts.events;

import java.util.UUID;

public record CourierStatusChangedEvent(
        UUID courierId,
        String fromStatus,
        String toStatus,
        UUID activeShipmentId) {

    public static final String TYPE = "CourierStatusChanged";
    public static final int VERSION = 1;
}
