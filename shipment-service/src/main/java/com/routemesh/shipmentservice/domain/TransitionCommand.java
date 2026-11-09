package com.routemesh.shipmentservice.domain;

import java.util.UUID;

public record TransitionCommand(
        UUID shipmentId,
        ShipmentStatus toStatus,
        Actor actor,
        String transitionKey,
        String reason,
        UUID courierId,
        ShipmentStatus expectedFromStatus) {
}
