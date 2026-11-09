package com.routemesh.ledgerservice.domain;

import java.time.Instant;
import java.util.UUID;

public record PostedTransaction(UUID id, boolean replayed, Instant createdAt) {
}
