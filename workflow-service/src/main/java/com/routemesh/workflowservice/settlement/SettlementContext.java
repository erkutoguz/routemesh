package com.routemesh.workflowservice.settlement;

/**
 * Hakedişe esas veriler. Zamanlar epoch milisaniye; 0: henüz olmadı.
 *
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
