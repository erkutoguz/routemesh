package com.routemesh.paymentservice.ledger;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Defterin gönderi başına {@code ASSET:PSP_CLEARING} net borcunu okur (yalnızca okuma, mutabakat çapraz kontrolü). */
public interface LedgerClearingClient {

    /** İstenen her gönderi için bir değer döner (defterde kaydı yoksa 0); eksik yanıt hatadır. */
    Map<UUID, Long> clearingNetDebit(Collection<UUID> shipmentIds);
}
