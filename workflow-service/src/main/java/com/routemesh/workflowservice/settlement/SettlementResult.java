package com.routemesh.workflowservice.settlement;

public record SettlementResult(
        String shipmentId,
        long merchantMinor,
        long courierMinor,
        long platformMinor,
        long slaPenaltyMinor) {
}
