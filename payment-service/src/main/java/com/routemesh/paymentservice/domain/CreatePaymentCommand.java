package com.routemesh.paymentservice.domain;

import java.util.UUID;

public record CreatePaymentCommand(String idempotencyKey, UUID shipmentId, long amountMinor, String currency) {
}
