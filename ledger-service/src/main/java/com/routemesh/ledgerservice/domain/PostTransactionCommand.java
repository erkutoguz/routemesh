package com.routemesh.ledgerservice.domain;

import java.util.List;
import java.util.UUID;

public record PostTransactionCommand(
        String idempotencyKey,
        String transactionType,
        UUID shipmentId,
        UUID reversalOf,
        List<EntryCommand> entries) {
}
