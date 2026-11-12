package com.routemesh.contracts.events;

import java.util.List;
import java.util.UUID;

/**
 * Defter bütünlük denetimi ihlal buldu. {@code ledger.events} topic'ine, {@code runId} anahtarıyla yazılır.
 * Yalnızca özet taşır (ayrıntı OPS uçlarında); kişisel veri içermez.
 */
public record LedgerIntegrityViolationDetectedEvent(
        UUID runId,
        String mode,
        int findingsCount,
        int criticalCount,
        List<String> types) {

    public static final String TYPE = "LedgerIntegrityViolationDetected";
    public static final int VERSION = 1;
}
