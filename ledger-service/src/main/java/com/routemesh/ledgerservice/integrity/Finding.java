package com.routemesh.ledgerservice.integrity;

import java.util.Map;
import java.util.UUID;

public record Finding(
        String type,
        String severity,
        String accountCode,
        UUID shipmentId,
        UUID transactionId,
        Long expectedMinor,
        Long actualMinor,
        Map<String, Object> detail) {

    public static final String UNBALANCED_TRANSACTION = "UNBALANCED_TRANSACTION";
    public static final String TOTALS_MISMATCH = "TOTALS_MISMATCH";
    public static final String NEGATIVE_BALANCE = "NEGATIVE_BALANCE";
    public static final String REVERSAL_MISMATCH = "REVERSAL_MISMATCH";
    public static final String HISTORY_TAMPERED = "HISTORY_TAMPERED";
    public static final String CACHE_DRIFT = "CACHE_DRIFT";
    public static final String TRUNCATED = "TRUNCATED";

    public static final String CRITICAL = "CRITICAL";
    public static final String WARNING = "WARNING";

    public static Finding critical(String type, String accountCode, UUID shipmentId, UUID transactionId,
                                   Long expected, Long actual, Map<String, Object> detail) {
        return new Finding(type, CRITICAL, accountCode, shipmentId, transactionId, expected, actual, detail);
    }
}
