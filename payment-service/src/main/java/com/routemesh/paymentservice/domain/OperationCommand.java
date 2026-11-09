package com.routemesh.paymentservice.domain;

import java.util.UUID;

public record OperationCommand(UUID paymentId, String idempotencyKey, long amountMinor, String currency,
                               String reason) {
}
