package com.routemesh.shipmentservice.rest;

import java.util.UUID;

import com.routemesh.shipmentservice.domain.ShipmentStatus;

public record TransitionRequest(
        ShipmentStatus toStatus,
        String reason,
        UUID courierId,
        ShipmentStatus expectedFromStatus) {
}
