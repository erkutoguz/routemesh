package com.routemesh.pspsimulator;

public class SimPayment {

    public enum Status {
        CREATED,
        AUTHORIZED,
        DECLINED,
        CAPTURED,
        VOIDED
    }

    private final String id;
    private final long amountMinor;
    private final String currency;
    private final String callbackUrl;
    private final String reference;
    private Status status = Status.CREATED;
    private long refundedMinor;

    public SimPayment(String id, long amountMinor, String currency, String callbackUrl, String reference) {
        this.id = id;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.callbackUrl = callbackUrl;
        this.reference = reference;
    }

    public String id() {
        return id;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public String callbackUrl() {
        return callbackUrl;
    }

    public String reference() {
        return reference;
    }

    public Status status() {
        return status;
    }

    public void status(Status status) {
        this.status = status;
    }

    public long refundedMinor() {
        return refundedMinor;
    }

    public void addRefunded(long amount) {
        this.refundedMinor += amount;
    }
}
