package com.routemesh.paymentservice.service;

public class PaymentException extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        ILLEGAL_STATE,
        PSP_REJECTED,
        PSP_UNAVAILABLE
    }

    private final Reason reason;

    public PaymentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
