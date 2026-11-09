package com.routemesh.paymentservice.domain;

import java.time.Instant;
import java.util.UUID;

public record PaymentIntent(
        UUID id,
        UUID shipmentId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String pspPaymentId,
        String checkoutUrl,
        long capturedMinor,
        long refundedMinor,
        String failureReason,
        long version,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {
}
