package com.routemesh.paymentservice.reconciliation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.domain.PaymentStatus;
import com.routemesh.paymentservice.psp.PspClient.PspPayment;

/**
 * Platformdaki ödeme niyeti ile PSP kaydının karşılaştırma kuralları. Saf işlevlerdir (veritabanı ve ağ yok).
 * İki taraftan birinin son güncellemesi {@code grace} içindeyse uçuşta olabilecek bir webhook'a pay bırakılır
 * ve bulgu üretilmez.
 */
final class ReconciliationRules {

    private ReconciliationRules() {
    }

    /** Niyet durumuna göre PSP'nin kalıcı olarak göstermesi beklenen durumlar. */
    static Set<String> expectedPspStatuses(PaymentStatus status) {
        return switch (status) {
            case CREATED -> Set.of("CREATED");
            case AUTHORIZED -> Set.of("AUTHORIZED");
            case CAPTURED, PARTIALLY_REFUNDED, REFUNDED -> Set.of("CAPTURED");
            case VOIDED -> Set.of("VOIDED");
            case EXPIRED -> Set.of("CREATED", "DECLINED", "VOIDED");
            case FAILED -> Set.of("DECLINED", "VOIDED");
        };
    }

    static boolean settling(Instant first, Instant second, Instant now, Duration grace) {
        Instant newest = first.isAfter(second) ? first : second;
        return newest.isAfter(now.minus(grace));
    }

    static List<Finding> compare(PaymentIntent intent, PspPayment psp, Instant now, Duration grace) {
        if (settling(intent.updatedAt(), psp.updatedAt(), now, grace)) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        Set<String> expected = expectedPspStatuses(intent.status());
        if (!expected.contains(psp.status())) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("expectedPspStatuses", expected.stream().sorted().toList());
            detail.put("localUpdatedAt", intent.updatedAt());
            detail.put("pspUpdatedAt", psp.updatedAt());
            findings.add(finding(Finding.STATUS_MISMATCH, intent, psp, detail));
        }
        if (psp.capturedMinor() != intent.capturedMinor()) {
            findings.add(finding(Finding.CAPTURED_AMOUNT_MISMATCH, intent, psp,
                    Map.of("differenceMinor", psp.capturedMinor() - intent.capturedMinor())));
        }
        if (psp.refundedMinor() != intent.refundedMinor()) {
            findings.add(finding(Finding.REFUNDED_AMOUNT_MISMATCH, intent, psp,
                    Map.of("differenceMinor", psp.refundedMinor() - intent.refundedMinor())));
        }
        return findings;
    }

    /** PSP'de var, platformda hiç kaydı yok. */
    static Optional<Finding> unknownAtPlatform(PspPayment psp, Instant now, Duration grace) {
        if (psp.updatedAt().isAfter(now.minus(grace))) {
            return Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reference", psp.reference());
        detail.put("amountMinor", psp.amountMinor());
        detail.put("pspUpdatedAt", psp.updatedAt());
        return Optional.of(new Finding(Finding.UNKNOWN_AT_PLATFORM, Finding.CRITICAL, null, null, psp.id(), null,
                null, psp.status(), null, null, psp.capturedMinor(), psp.refundedMinor(), detail));
    }

    /** Platformda var, PSP'nin ne rapor ne de tek tek sorgu yanıtında kaydı yok. */
    static Finding missingAtPsp(PaymentIntent intent) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("amountMinor", intent.amountMinor());
        detail.put("localUpdatedAt", intent.updatedAt());
        return new Finding(Finding.MISSING_AT_PSP, Finding.CRITICAL, intent.id(), intent.shipmentId(),
                intent.pspPaymentId(), null, intent.status().name(), null, intent.capturedMinor(),
                intent.refundedMinor(), null, null, detail);
    }

    private static Finding finding(String type, PaymentIntent intent, PspPayment psp, Map<String, Object> detail) {
        return new Finding(type, Finding.CRITICAL, intent.id(), intent.shipmentId(), intent.pspPaymentId(), null,
                intent.status().name(), psp.status(), intent.capturedMinor(), intent.refundedMinor(),
                psp.capturedMinor(), psp.refundedMinor(), detail);
    }
}
