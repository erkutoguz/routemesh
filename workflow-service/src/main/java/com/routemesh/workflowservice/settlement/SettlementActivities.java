package com.routemesh.workflowservice.settlement;

import java.util.List;
import java.util.UUID;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface SettlementActivities {

    /**
     * Gönderiyi, quote'taki SLA süresini ve ledger'daki escrow bakiyesini toplar. Gönderi {@code DELIVERED} değilse,
     * escrow bakiyesi tutara eşit ya da (daha önce dağıtıldıysa) sıfır değilse, depo kabul/teslim zamanı yoksa
     * yeniden denenmez.
     */
    SettlementContext load(UUID shipmentId);

    /** Ledger işlemi; idempotency anahtarı {@code workflowId:işlem_tipi}. */
    void postLedger(UUID shipmentId, String transactionType, List<SettlementTemplates.Line> lines);

    /** Gönderinin yakalanmış ödemesinden PSP iadesi; anahtar {@code workflowId:işlem_tipi:refund}. */
    void refundPayment(UUID shipmentId, String transactionType, long amountMinor, String reason);
}
