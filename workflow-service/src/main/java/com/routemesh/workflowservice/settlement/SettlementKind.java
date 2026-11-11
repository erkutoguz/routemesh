package com.routemesh.workflowservice.settlement;

/** Escrow'un hangi sonla dağıtılacağı; gönderinin son durumuna ve geldiği duruma göre belirlenir. */
public enum SettlementKind {
    /** {@code DELIVERED}: satıcı, kurye ve platforma dağıtım (+ SLA cezası). */
    DELIVERY,
    /** {@code RETURN_TO_WAREHOUSE → CANCELLED}: kurye payı korunur, platform kesintisi, kalan müşteriye iade. */
    RETURN,
    /** {@code LOST}: müşteriye tam iade, satıcıya tazminat gider hesabından. */
    LOST,
    /** Teslimattan önce {@code CANCELLED} (hasar dahil): müşteriye tam iade. */
    CANCEL
}
