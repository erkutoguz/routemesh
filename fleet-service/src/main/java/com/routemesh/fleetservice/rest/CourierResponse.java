package com.routemesh.fleetservice.rest;

import java.util.UUID;

import com.routemesh.fleetservice.domain.Courier;

record CourierResponse(UUID courierId, String status, int maxWeightGrams, UUID activeShipmentId) {

    static CourierResponse from(Courier courier) {
        return new CourierResponse(courier.courierId(), courier.status().name(), courier.maxWeightGrams(),
                courier.activeShipmentId());
    }
}
