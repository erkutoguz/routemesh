package com.routemesh.shipmentservice.service;

public class ShipmentException extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        ILLEGAL_TRANSITION,
        IDEMPOTENCY_CONFLICT
    }

    private final Reason reason;

    public ShipmentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
