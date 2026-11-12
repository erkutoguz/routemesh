package com.routemesh.paymentservice.reconciliation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("routemesh.payment.reconciliation")
public record ReconciliationProperties(
        boolean schedulerEnabled,
        Duration interval,
        Duration initialDelay,
        Duration overlap,
        Duration initialLookback,
        Duration grace,
        Duration stuckAfter,
        Duration staleRunAfter,
        int pageSize,
        int maxFindingsPerRun,
        int missingFloodThreshold,
        int maxLookups,
        Duration maxManualWindow,
        boolean ledgerCheckEnabled,
        int ledgerBatchSize) {
}
