package com.routemesh.ledgerservice.integrity;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("routemesh.ledger.integrity")
public record IntegrityProperties(
        boolean schedulerEnabled,
        Duration incrementalInterval,
        Duration incrementalInitialDelay,
        String fullCron,
        int chunkEntries,
        Duration lockTimeout,
        Duration staleRunAfter,
        int maxFindingsPerRun) {
}
