package com.routemesh.workflowservice.settlement;

/**
 * @param customerRefundMinor müşteriye PSP üzerinden iade edilen toplam tutar (teslimatta yalnızca SLA cezası)
 * @param slaPenaltyMinor     yalnızca teslimatta; platform komisyonundan feragatle iade edilen ceza
 */
public record SettlementResult(
        String shipmentId,
        SettlementKind kind,
        long merchantMinor,
        long courierMinor,
        long platformMinor,
        long customerRefundMinor,
        long slaPenaltyMinor) {
}
