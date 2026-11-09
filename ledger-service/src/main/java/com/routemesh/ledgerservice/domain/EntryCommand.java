package com.routemesh.ledgerservice.domain;

public record EntryCommand(String accountCode, Direction direction, long amountMinor, String currency) {
}
