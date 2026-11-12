package com.routemesh.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PSP mutabakat koşusu bulgu üretti (tutarsızlık, takılı işlem ya da PSP raporuna ulaşılamadı).
 * {@code payment.events} topic'ine, {@code runId} anahtarıyla yazılır. Yalnızca özet taşır, ayrıntı OPS uçlarında.
 */
public record ReconciliationDiscrepancyDetectedEvent(
        UUID runId,
        String status,
        int findingsCount,
        int criticalCount,
        List<String> types,
        Instant windowFrom,
        Instant windowTo) {

    public static final String TYPE = "ReconciliationDiscrepancyDetected";
    public static final int VERSION = 1;
}
