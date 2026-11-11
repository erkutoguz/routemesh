package com.routemesh.workflowservice.settlement;

/**
 * Hakedişe esas veriler. Zamanlar epoch milisaniye; yalnızca teslimatta dolu (diğerlerinde 0).
 *
 * @param courierId          teslimat ve iadede dolu, diğerlerinde boş
 * @param escrowBalanceMinor ledger'daki gönderiye ait escrow bakiyesi (dağıtımdan sonra 0)
 */
public record SettlementContext(
        String shipmentId,
        String merchantId,
        String courierId,
        long amountMinor,
        long escrowBalanceMinor,
        long warehouseReceivedAtMillis,
        long deliveredAtMillis,
        int slaMinutes) {
}
