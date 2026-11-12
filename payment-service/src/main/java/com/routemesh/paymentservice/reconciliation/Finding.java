package com.routemesh.paymentservice.reconciliation;

import java.util.Map;
import java.util.UUID;

public record Finding(
        String type,
        String severity,
        UUID intentId,
        UUID shipmentId,
        String pspPaymentId,
        UUID transactionId,
        String localStatus,
        String pspStatus,
        Long localCapturedMinor,
        Long localRefundedMinor,
        Long pspCapturedMinor,
        Long pspRefundedMinor,
        Map<String, Object> detail) {

    public static final String MISSING_AT_PSP = "MISSING_AT_PSP";
    public static final String UNKNOWN_AT_PLATFORM = "UNKNOWN_AT_PLATFORM";
    public static final String STATUS_MISMATCH = "STATUS_MISMATCH";
    public static final String CAPTURED_AMOUNT_MISMATCH = "CAPTURED_AMOUNT_MISMATCH";
    public static final String REFUNDED_AMOUNT_MISMATCH = "REFUNDED_AMOUNT_MISMATCH";
    public static final String STUCK_TRANSACTION = "STUCK_TRANSACTION";
    public static final String PSP_REPORT_UNAVAILABLE = "PSP_REPORT_UNAVAILABLE";
    public static final String TRUNCATED = "TRUNCATED";

    public static final String CRITICAL = "CRITICAL";
    public static final String WARNING = "WARNING";
}
