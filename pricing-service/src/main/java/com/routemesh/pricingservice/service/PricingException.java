package com.routemesh.pricingservice.service;

public class PricingException extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        OUT_OF_SERVICE_AREA,
        QUOTE_EXPIRED,
        IDEMPOTENCY_CONFLICT,
        ALREADY_REDEEMED
    }

    private final Reason reason;

    public PricingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
