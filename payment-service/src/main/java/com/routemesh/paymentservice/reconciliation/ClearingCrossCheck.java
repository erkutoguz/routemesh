package com.routemesh.paymentservice.reconciliation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.ledger.LedgerClearingClient;
import com.routemesh.paymentservice.repository.PaymentRepository;

/**
 * Üçlü mutabakatın defter ayağı: bir gönderinin yerel ödemelerinin net tutarı ({@code captured - refunded}, tüm
 * ödeme denemeleri toplamı) ile defterdeki {@code ASSET:PSP_CLEARING} gönderi net borcu eşit olmalıdır. Yakalama
 * ve iade kayıtları defterde ayrı bir adımla (workflow) yazıldığı için, son güncellemesi {@code grace} içinde olan
 * ödemesi bulunan gönderiler değerlendirilmez. Defter erişilemezse koşu çökmez, tek {@code LEDGER_UNAVAILABLE}
 * uyarısı yazılır.
 */
@Component
class ClearingCrossCheck {

    private static final Logger log = LoggerFactory.getLogger(ClearingCrossCheck.class);
    private static final int SHIPMENT_BATCH = 500;

    private final PaymentRepository payments;
    private final LedgerClearingClient ledger;
    private final ReconciliationProperties properties;

    ClearingCrossCheck(PaymentRepository payments, LedgerClearingClient ledger, ReconciliationProperties properties) {
        this.payments = payments;
        this.ledger = ledger;
        this.properties = properties;
    }

    List<Finding> check(List<PaymentIntent> windowIntents, Instant now) {
        if (!properties.ledgerCheckEnabled()) {
            return List.of();
        }
        Instant cutoff = now.minus(properties.grace());
        Set<UUID> candidates = new LinkedHashSet<>();
        windowIntents.stream().filter(intent -> !intent.updatedAt().isAfter(cutoff))
                .forEach(intent -> candidates.add(intent.shipmentId()));
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<UUID> shipmentIds = new ArrayList<>(candidates);
        List<Finding> findings = new ArrayList<>();
        try {
            for (int from = 0; from < shipmentIds.size(); from += SHIPMENT_BATCH) {
                List<UUID> batch = shipmentIds.subList(from, Math.min(shipmentIds.size(), from + SHIPMENT_BATCH));
                compare(batch, cutoff, findings);
            }
        } catch (RuntimeException e) {
            log.warn("Ledger clearing cross-check could not be completed: {}", e.toString());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", reasonOf(e));
            detail.put("error", String.valueOf(e.getMessage()));
            detail.put("shipmentsInWindow", shipmentIds.size());
            return List.of(new Finding(Finding.LEDGER_UNAVAILABLE, Finding.WARNING, null, null, null, null, null,
                    null, null, null, null, null, detail));
        }
        return findings;
    }

    private void compare(List<UUID> shipmentIds, Instant cutoff, List<Finding> findings) {
        Map<UUID, List<PaymentIntent>> byShipment = new LinkedHashMap<>();
        for (PaymentIntent intent : payments.findIntentsByShipmentIds(shipmentIds)) {
            byShipment.computeIfAbsent(intent.shipmentId(), id -> new ArrayList<>()).add(intent);
        }
        List<UUID> settled = new ArrayList<>();
        for (UUID shipmentId : shipmentIds) {
            List<PaymentIntent> intents = byShipment.getOrDefault(shipmentId, List.of());
            if (!intents.isEmpty() && intents.stream().noneMatch(intent -> intent.updatedAt().isAfter(cutoff))) {
                settled.add(shipmentId);
            }
        }
        if (settled.isEmpty()) {
            return;
        }
        Map<UUID, Long> observed = ledger.clearingNetDebit(settled);
        for (UUID shipmentId : settled) {
            List<PaymentIntent> intents = byShipment.get(shipmentId);
            long captured = intents.stream().mapToLong(PaymentIntent::capturedMinor).sum();
            long refunded = intents.stream().mapToLong(PaymentIntent::refundedMinor).sum();
            long expected = captured - refunded;
            long clearing = observed.getOrDefault(shipmentId, 0L);
            if (expected != clearing) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("expectedNetMinor", expected);
                detail.put("ledgerClearingNetMinor", clearing);
                detail.put("differenceMinor", clearing - expected);
                detail.put("intentIds", intents.stream().map(PaymentIntent::id).map(UUID::toString).toList());
                findings.add(new Finding(Finding.CLEARING_MISMATCH, Finding.CRITICAL,
                        intents.size() == 1 ? intents.getFirst().id() : null, shipmentId, null, null, null, null,
                        captured, refunded, null, null, detail));
            }
        }
    }

    private static String reasonOf(RuntimeException e) {
        if (e instanceof io.grpc.StatusRuntimeException status) {
            return switch (status.getStatus().getCode()) {
                case UNAVAILABLE, DEADLINE_EXCEEDED -> "UNREACHABLE";
                case UNAUTHENTICATED, PERMISSION_DENIED -> "NOT_AUTHORIZED";
                default -> "REJECTED_" + status.getStatus().getCode();
            };
        }
        return e instanceof IllegalStateException ? "BAD_RESPONSE_OR_TOKEN" : "ERROR";
    }
}
