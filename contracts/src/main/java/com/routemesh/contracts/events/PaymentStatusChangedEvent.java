package com.routemesh.contracts.events;

import java.util.UUID;

public record PaymentStatusChangedEvent(
        UUID paymentId,
        UUID shipmentId,
        String fromStatus,
        String toStatus,
        long amountMinor,
        long refundedMinor,
        String currency,
        String reason,
        long version) {

    public static final String TYPE = "PaymentStatusChanged";
    public static final int VERSION = 1;
}
