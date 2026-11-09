package com.routemesh.paymentservice.domain;

import java.util.UUID;

public record PaymentTransaction(
        UUID id,
        UUID intentId,
        TxnType type,
        String commandKey,
        String requestHash,
        long amountMinor,
        TxnStatus status,
        String reason,
        String pspReference,
        String lastError,
        int attempts) {
}
