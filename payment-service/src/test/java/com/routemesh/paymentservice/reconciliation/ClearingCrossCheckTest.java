package com.routemesh.paymentservice.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.routemesh.paymentservice.FakeLedgerClearingClient;
import com.routemesh.paymentservice.PaymentTestBase;
import com.routemesh.paymentservice.psp.PspClient.PspPayment;
import com.routemesh.paymentservice.reconciliation.ReconciliationRepository.Run;
import com.routemesh.paymentservice.reconciliation.ReconciliationService.Trigger;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** Üçlü mutabakatın defter ayağı: yerel net ödeme tutarı ile defterdeki PSP clearing bakiyesi. */
class ClearingCrossCheckTest extends PaymentTestBase {

    private static final AtomicInteger WINDOWS = new AtomicInteger();

    @Autowired
    private ReconciliationService service;

    @Autowired
    private FakeLedgerClearingClient ledger;

    private final Instant base = Instant.now().minus(Duration.ofDays(90))
            .minus(Duration.ofHours(3L * WINDOWS.incrementAndGet())).truncatedTo(ChronoUnit.MICROS);

    @BeforeEach
    void resetLedger() {
        ledger.reset();
    }

    @AfterEach
    void cleanUp() {
        ledger.reset();
        jdbc.update("UPDATE reconciliation_runs SET status = 'FAILED', finished_at = now() WHERE status = 'RUNNING'");
    }

    // --- eşleşenler ---

    @Test
    void matchingLedgerBalancesProduceNoFinding() {
        UUID captured = shipment();
        UUID refunded = shipment();
        UUID fullyRefunded = shipment();
        UUID unpaid = shipment();
        intent(captured, "CAPTURED", 1000, 0, base);
        intent(refunded, "PARTIALLY_REFUNDED", 1000, 300, base);
        intent(fullyRefunded, "REFUNDED", 1000, 1000, base);
        intent(unpaid, "CREATED", 0, 0, base);

        Run run = run();

        assertThat(run.status()).isEqualTo("OK");
        assertThat(types(run)).isEmpty();
        assertThat(ledger.askedShipments()).containsExactlyInAnyOrder(captured, refunded, fullyRefunded, unpaid);
    }

    // --- uyuşmazlıklar ---

    @Test
    void aMissingRefundPostingInTheLedgerIsAClearingMismatch() {
        UUID shipment = shipment();
        UUID intent = intent(shipment, "PARTIALLY_REFUNDED", 1000, 300, base);
        ledger.ledgerHas(shipment, 1000);

        Run run = run();

        assertThat(run.status()).isEqualTo("DISCREPANCIES");
        Map<String, Object> finding = jdbc.queryForMap("SELECT type, severity, shipment_id, intent_id, "
                + "local_captured_minor, local_refunded_minor, detail::text AS detail FROM reconciliation_findings "
                + "WHERE run_id = ?", run.id());
        assertThat(finding).containsEntry("type", Finding.CLEARING_MISMATCH).containsEntry("severity", "CRITICAL")
                .containsEntry("shipment_id", shipment).containsEntry("intent_id", intent)
                .containsEntry("local_captured_minor", 1000L).containsEntry("local_refunded_minor", 300L);
        assertThat(finding.get("detail").toString()).contains("\"expectedNetMinor\": 700")
                .contains("\"ledgerClearingNetMinor\": 1000").contains("\"differenceMinor\": 300");
    }

    @Test
    void anExtraOrMissingClearingPostingIsReportedWithItsSign() {
        UUID extra = shipment();
        UUID missing = shipment();
        UUID refundNotPosted = shipment();
        intent(extra, "CAPTURED", 1000, 0, base);
        intent(missing, "CAPTURED", 1000, 0, base);
        intent(refundNotPosted, "REFUNDED", 1000, 1000, base);
        ledger.ledgerHas(extra, 1500);
        ledger.ledgerHas(missing, 0);
        ledger.ledgerHas(refundNotPosted, 1000);

        Run run = run();

        Map<UUID, Long> differences = new java.util.HashMap<>();
        jdbc.query("SELECT shipment_id, (detail->>'differenceMinor')::bigint AS d FROM reconciliation_findings "
                + "WHERE run_id = ? AND type = 'CLEARING_MISMATCH'", rs -> {
            differences.put(rs.getObject("shipment_id", UUID.class), rs.getLong("d"));
        }, run.id());
        assertThat(differences).containsOnlyKeys(extra, missing, refundNotPosted)
                .containsEntry(extra, 500L).containsEntry(missing, -1000L).containsEntry(refundNotPosted, 1000L);
    }

    @Test
    void clearingMoneyOnAShipmentWithoutAnyCapturedPaymentIsAMismatch() {
        UUID shipment = shipment();
        intent(shipment, "CREATED", 0, 0, base);
        ledger.ledgerHas(shipment, 100);

        Run run = run();

        assertThat(types(run)).containsExactly(Finding.CLEARING_MISMATCH);
    }

    @Test
    void allPaymentAttemptsOfAShipmentAreSummedBeforeComparing() {
        UUID shipment = shipment();
        intent(shipment, "FAILED", 0, 0, base);
        intent(shipment, "EXPIRED", 0, 0, base);
        UUID paid = intent(shipment, "CAPTURED", 1000, 0, base);

        assertThat(types(run())).isEmpty();

        ledger.ledgerHas(shipment, 700);
        Run mismatched = run();
        Map<String, Object> finding = jdbc.queryForMap("SELECT intent_id, detail::text AS detail FROM "
                + "reconciliation_findings WHERE run_id = ? AND type = 'CLEARING_MISMATCH'", mismatched.id());
        assertThat(finding.get("intent_id")).isNull();
        assertThat(finding.get("detail").toString()).contains("\"expectedNetMinor\": 1000")
                .contains("\"ledgerClearingNetMinor\": 700").contains(paid.toString());
    }

    // --- uçuştakiler ve pencere ---

    @Test
    void shipmentsWithARecentlyChangedPaymentAreLeftAloneWhileTheLedgerCatchesUp() {
        Instant recent = Instant.now().minus(Duration.ofMinutes(2));
        UUID allRecent = shipment();
        UUID mixed = shipment();
        UUID settled = shipment();
        intent(allRecent, "CAPTURED", 1000, 0, recent);
        intent(mixed, "FAILED", 0, 0, base);
        intent(mixed, "CAPTURED", 1000, 0, recent);
        intent(settled, "CAPTURED", 1000, 0, base);
        ledger.ledgerHas(allRecent, 0);
        ledger.ledgerHas(mixed, 0);

        Run run = service.run(Trigger.MANUAL, "ops@test", base.minusSeconds(3600), Instant.now().plusSeconds(60));

        assertThat(types(run)).doesNotContain(Finding.CLEARING_MISMATCH);
        assertThat(ledger.askedShipments()).contains(settled).doesNotContain(allRecent, mixed);
    }

    @Test
    void onlyShipmentsWithPaymentChangesInTheWindowAreChecked() {
        UUID inside = shipment();
        UUID outside = shipment();
        intent(inside, "CAPTURED", 1000, 0, base);
        intent(outside, "CAPTURED", 1000, 0, base.minus(Duration.ofDays(3)));
        ledger.ledgerHas(outside, 0);

        Run run = run();

        assertThat(types(run)).isEmpty();
        assertThat(ledger.askedShipments()).containsExactly(inside);
    }

    // --- defter erişilemiyor ---

    @Test
    void anUnreachableLedgerBecomesOneWarningAndThePspLegStillRuns() {
        UUID shipment = shipment();
        intent(shipment, "CAPTURED", 1000, 0, base);
        pspMismatch();
        ledger.failWith(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("connection refused")));

        Run run = run();

        assertThat(run.status()).isEqualTo("DISCREPANCIES");
        assertThat(types(run)).containsExactlyInAnyOrder(Finding.LEDGER_UNAVAILABLE, Finding.STATUS_MISMATCH);
        Map<String, Object> warning = jdbc.queryForMap("SELECT severity, detail::text AS detail FROM "
                + "reconciliation_findings WHERE run_id = ? AND type = 'LEDGER_UNAVAILABLE'", run.id());
        assertThat(warning).containsEntry("severity", "WARNING");
        assertThat(warning.get("detail").toString()).contains("UNREACHABLE").contains("\"shipmentsInWindow\": 2");
    }

    @Test
    void theFailureKindIsNamedInTheWarning() {
        intent(shipment(), "CAPTURED", 1000, 0, base);
        record Case(RuntimeException failure, String reason) {
        }
        for (Case c : List.of(
                new Case(new StatusRuntimeException(Status.DEADLINE_EXCEEDED), "UNREACHABLE"),
                new Case(new StatusRuntimeException(Status.PERMISSION_DENIED), "NOT_AUTHORIZED"),
                new Case(new StatusRuntimeException(Status.UNAUTHENTICATED), "NOT_AUTHORIZED"),
                new Case(new StatusRuntimeException(Status.INVALID_ARGUMENT), "REJECTED_INVALID_ARGUMENT"),
                new Case(new IllegalStateException("token endpoint returned no access_token"),
                        "BAD_RESPONSE_OR_TOKEN"))) {
            ledger.failWith(c.failure());
            Run run = run();

            assertThat(jdbc.queryForObject("SELECT detail->>'reason' FROM reconciliation_findings WHERE run_id = ? "
                    + "AND type = 'LEDGER_UNAVAILABLE'", String.class, run.id())).isEqualTo(c.reason());
            assertThat(run.status()).isEqualTo("DISCREPANCIES");
        }
    }

    @Test
    void whenBothThePspAndTheLedgerAreDownTheRunFailsWithBothFindings() {
        intent(shipment(), "CAPTURED", 1000, 0, base);
        psp.reportFails(true);
        ledger.failWith(new StatusRuntimeException(Status.UNAVAILABLE));

        Run run = run();

        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(types(run)).containsExactlyInAnyOrder(Finding.LEDGER_UNAVAILABLE, Finding.PSP_REPORT_UNAVAILABLE);
    }

    @Test
    void theAlarmEventNamesTheClearingFindings() {
        UUID shipment = shipment();
        intent(shipment, "CAPTURED", 1000, 0, base);
        ledger.ledgerHas(shipment, 0);

        Run run = run();

        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ?",
                String.class, run.id().toString());
        assertThat(jsonMapper.readTree(payload).get("data").get("types")).extracting(node -> node.asString())
                .containsExactly("CLEARING_MISMATCH");
        assertThat(payload).doesNotContain(shipment.toString());
    }

    // --- yardımcılar ---

    private Run run() {
        return service.run(Trigger.MANUAL, "ops@test", base.minusSeconds(3600), base.plusSeconds(3600));
    }

    private List<String> types(Run run) {
        return jdbc.queryForList("SELECT type FROM reconciliation_findings WHERE run_id = ? ORDER BY id", String.class,
                run.id());
    }

    private static UUID shipment() {
        return UUID.randomUUID();
    }

    /** PSP tarafıyla tutarlı bir niyet ekler (PSP bacağı bulgu üretmez). */
    private UUID intent(UUID shipmentId, String status, long captured, long refunded, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        String pspId = "psp_cc_" + id;
        jdbc.update("INSERT INTO payment_intents (id, idempotency_key, request_hash, shipment_id, amount_minor, "
                        + "currency, status, psp_payment_id, checkout_url, captured_minor, refunded_minor, "
                        + "expires_at, created_at, updated_at) VALUES (?, ?, 'h', ?, 1000, 'TRY', ?, ?, 'http://x', "
                        + "?, ?, ?, ?, ?)", id, UUID.randomUUID().toString(), shipmentId, status, pspId, captured,
                refunded, Timestamp.from(updatedAt.plusSeconds(900)), Timestamp.from(updatedAt),
                Timestamp.from(updatedAt));
        String pspStatus = switch (status) {
            case "CAPTURED", "PARTIALLY_REFUNDED", "REFUNDED" -> "CAPTURED";
            case "FAILED" -> "DECLINED";
            default -> "CREATED";
        };
        psp.pspHas(new PspPayment(pspId, "ref", pspStatus, 1000, captured, refunded, updatedAt, updatedAt));
        return id;
    }

    private void pspMismatch() {
        UUID id = UUID.randomUUID();
        String pspId = "psp_cc_mm_" + id;
        jdbc.update("INSERT INTO payment_intents (id, idempotency_key, request_hash, shipment_id, amount_minor, "
                        + "currency, status, psp_payment_id, checkout_url, captured_minor, refunded_minor, "
                        + "expires_at, created_at, updated_at) VALUES (?, ?, 'h', ?, 1000, 'TRY', 'AUTHORIZED', ?, "
                        + "'http://x', 0, 0, ?, ?, ?)", id, UUID.randomUUID().toString(), shipment(), pspId,
                Timestamp.from(base.plusSeconds(900)), Timestamp.from(base), Timestamp.from(base));
        psp.pspHas(new PspPayment(pspId, "ref", "CAPTURED", 1000, 0, 0, base, base));
    }
}
