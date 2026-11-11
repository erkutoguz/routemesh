package com.routemesh.shipmentservice.rest;

import java.util.List;
import java.util.UUID;

/**
 * @param result {@code OK} (uygulandı), {@code REPLAYED} (aynı anahtarla daha önce uygulanmıştı) ya da
 *               {@code REJECTED} ({@code code}: NOT_FOUND, ILLEGAL_TRANSITION, IDEMPOTENCY_CONFLICT, INVALID_REQUEST)
 */
record BulkTransitionResponse(int applied, int rejected, List<Item> results) {

    record Item(UUID shipmentId, String result, String status, String code, String reason) {

        static Item applied(UUID shipmentId, boolean replayed, String status) {
            return new Item(shipmentId, replayed ? "REPLAYED" : "OK", status, null, null);
        }

        static Item rejected(UUID shipmentId, String code, String reason) {
            return new Item(shipmentId, "REJECTED", null, code, reason);
        }
    }

    static BulkTransitionResponse of(List<Item> results) {
        int rejected = (int) results.stream().filter(item -> item.result().equals("REJECTED")).count();
        return new BulkTransitionResponse(results.size() - rejected, rejected, results);
    }
}
