package com.routemesh.fleetservice.service;

public class FleetException extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        PRECONDITION_FAILED,
        CONFLICT,
        UNAVAILABLE
    }

    private final Reason reason;

    public FleetException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
