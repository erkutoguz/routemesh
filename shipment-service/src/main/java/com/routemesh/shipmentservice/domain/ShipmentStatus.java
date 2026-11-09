package com.routemesh.shipmentservice.domain;

public enum ShipmentStatus {
    PENDING_PAYMENT,
    PAID,
    AT_WAREHOUSE,
    READY_FOR_DISPATCH,
    ASSIGNED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    DELIVERY_FAILED,
    RETURN_TO_WAREHOUSE,
    DAMAGED,
    ON_HOLD,
    CANCELLED,
    LOST
}
