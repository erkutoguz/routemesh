package com.routemesh.contracts.events;

import java.util.List;
import java.util.UUID;

public record LedgerTransactionPostedEvent(
        UUID transactionId,
        String transactionType,
        UUID shipmentId,
        UUID reversalOf,
        String currency,
        List<Entry> entries) {

    public static final String TYPE = "LedgerTransactionPosted";
    public static final int VERSION = 1;

    public record Entry(String accountCode, String direction, long amountMinor) {
    }
}
