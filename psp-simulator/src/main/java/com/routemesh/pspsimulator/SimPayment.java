package com.routemesh.pspsimulator;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
    private final Clock clock;
    private final Instant createdAt;
    private Instant updatedAt;
    private Status status = Status.CREATED;
    private long capturedMinor;
    private long refundedMinor;

    public SimPayment(String id, long amountMinor, String currency, String callbackUrl, String reference,
                      Clock clock) {
        this.id = id;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.callbackUrl = callbackUrl;
        this.reference = reference;
        this.clock = clock;
        this.createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
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
        touch();
    }

    public void capture() {
        this.status = Status.CAPTURED;
        this.capturedMinor = amountMinor;
        touch();
    }

    public long capturedMinor() {
        return capturedMinor;
    }

    public long refundedMinor() {
        return refundedMinor;
    }

    public void addRefunded(long amount) {
        this.refundedMinor += amount;
        touch();
    }

    /** Hata enjeksiyonu: PSP tarafındaki değeri webhook göndermeden değiştirir. */
    public void tamper(Long capturedMinor, Long refundedMinor, Status status) {
        if (capturedMinor != null) {
            this.capturedMinor = capturedMinor;
        }
        if (refundedMinor != null) {
            this.refundedMinor = refundedMinor;
        }
        if (status != null) {
            this.status = status;
        }
        touch();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
