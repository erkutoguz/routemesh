package com.routemesh.ledgerservice.service;

public class LedgerException extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST,
        ACCOUNT_NOT_FOUND,
        INSUFFICIENT_FUNDS,
        IDEMPOTENCY_CONFLICT,
        INVALID_REVERSAL
    }

    private final Reason reason;

    public LedgerException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
