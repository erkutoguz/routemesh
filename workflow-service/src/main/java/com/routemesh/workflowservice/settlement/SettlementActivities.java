package com.routemesh.workflowservice.settlement;

import java.util.List;
import java.util.UUID;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface SettlementActivities {

    /**
     * Gönderiyi ve ledger'daki escrow bakiyesini toplar; teslimatta quote'taki SLA süresini de. Gönderi şablonun
     * beklediği durumda değilse ({@code DELIVERED}, {@code CANCELLED} ya da {@code LOST}), escrow bakiyesi tutara eşit
     * ya da (daha önce dağıtıldıysa) sıfır değilse, ya da gereken kurye/zaman bilgisi yoksa yeniden denenmez.
     */
    SettlementContext load(UUID shipmentId, SettlementKind kind);

    /** Ledger işlemi; idempotency anahtarı {@code workflowId:işlem_tipi}. */
    void postLedger(UUID shipmentId, String transactionType, List<SettlementTemplates.Line> lines);

    /** Gönderinin yakalanmış ödemesinden PSP iadesi; anahtar {@code workflowId:işlem_tipi:refund}. */
    void refundPayment(UUID shipmentId, String transactionType, long amountMinor, String reason);
}
