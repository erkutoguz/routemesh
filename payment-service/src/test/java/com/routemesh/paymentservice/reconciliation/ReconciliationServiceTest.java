package com.routemesh.paymentservice.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.routemesh.paymentservice.FakePspClient;
import com.routemesh.paymentservice.PaymentTestBase;
import com.routemesh.paymentservice.psp.PspClient.PspPayment;
import com.routemesh.paymentservice.reconciliation.ReconciliationRepository.Run;
import com.routemesh.paymentservice.reconciliation.ReconciliationService.RunAlreadyActiveException;
import com.routemesh.paymentservice.reconciliation.ReconciliationService.Trigger;

class ReconciliationServiceTest extends PaymentTestBase {

    private static final AtomicInteger WINDOWS = new AtomicInteger();

    @Autowired
    private ReconciliationService service;

    @Autowired
    private ReconciliationRepository repository;

    private final Instant base = Instant.now().minus(Duration.ofDays(30)).minus(Duration.ofHours(3L * WINDOWS.incrementAndGet()))
            .truncatedTo(ChronoUnit.MICROS);

    @AfterEach
    void neutralizeLeftoverOpenTransactions() {
        jdbc.update("UPDATE payment_transactions SET status = 'FAILED' WHERE status IN ('PENDING', 'IN_PROGRESS', "
                + "'UNKNOWN') AND intent_id IN (SELECT id FROM payment_intents WHERE checkout_url = 'http://x')");
        jdbc.update("UPDATE reconciliation_runs SET status = 'FAILED', finished_at = now() WHERE status = 'RUNNING'");
    }

    // --- eşleşen kayıtlar ---

    @Test
    void healthyPaymentsInEveryStatusProduceNoFindings() {
        pair("CREATED", 0, 0, "CREATED", 0, 0);
        pair("AUTHORIZED", 0, 0, "AUTHORIZED", 0, 0);
        pair("CAPTURED", 1000, 0, "CAPTURED", 1000, 0);
        pair("PARTIALLY_REFUNDED", 1000, 300, "CAPTURED", 1000, 300);
        pair("REFUNDED", 1000, 1000, "CAPTURED", 1000, 1000);
        pair("VOIDED", 0, 0, "VOIDED", 0, 0);
        pair("EXPIRED", 0, 0, "CREATED", 0, 0);
        pair("FAILED", 0, 0, "DECLINED", 0, 0);

        Run run = run();

        assertThat(run.status()).isEqualTo("OK");
        assertThat(run.pspPaymentsSeen()).isEqualTo(8);
        assertThat(run.intentsChecked()).isEqualTo(8);
        assertThat(run.findingsCount()).isZero();
        assertThat(findings(run)).isEmpty();
        assertThat(run.trigger()).isEqualTo("MANUAL");
        assertThat(run.requestedBy()).isEqualTo("ops@test");
    }

    @Test
    void aStatusAndAmountDisagreementIsReportedPerKind() {
        String statusOnly = pair("AUTHORIZED", 0, 0, "CAPTURED", 0, 0);
        String captured = pair("CAPTURED", 1000, 0, "CAPTURED", 900, 0);
        String refunded = pair("PARTIALLY_REFUNDED", 1000, 300, "CAPTURED", 1000, 500);
        String lostWebhook = pair("AUTHORIZED", 0, 0, "CAPTURED", 1000, 0);
        pair("CAPTURED", 1000, 0, "CAPTURED", 1000, 0);

        Run run = run();

        assertThat(run.status()).isEqualTo("DISCREPANCIES");
        assertThat(typesFor(run, statusOnly)).containsExactly(Finding.STATUS_MISMATCH);
        assertThat(typesFor(run, captured)).containsExactly(Finding.CAPTURED_AMOUNT_MISMATCH);
        assertThat(typesFor(run, refunded)).containsExactly(Finding.REFUNDED_AMOUNT_MISMATCH);
        assertThat(typesFor(run, lostWebhook))
                .containsExactlyInAnyOrder(Finding.STATUS_MISMATCH, Finding.CAPTURED_AMOUNT_MISMATCH);
        assertThat(run.findingsCount()).isEqualTo(5);
        Map<String, Object> row = jdbc.queryForMap("SELECT local_status, psp_status, local_captured_minor, "
                + "psp_captured_minor, severity, intent_id, shipment_id FROM reconciliation_findings "
                + "WHERE run_id = ? AND psp_payment_id = ?", run.id(), captured);
        assertThat(row).containsEntry("local_status", "CAPTURED").containsEntry("psp_status", "CAPTURED")
                .containsEntry("local_captured_minor", 1000L).containsEntry("psp_captured_minor", 900L)
                .containsEntry("severity", "CRITICAL");
        assertThat(row.get("intent_id")).isNotNull();
        assertThat(row.get("shipment_id")).isNotNull();
    }

    // --- tek taraflı kayıtlar ---

    @Test
    void aPspPaymentTheplatformNeverHeardOfIsReported() {
        String orphan = "psp_orphan_" + UUID.randomUUID();
        psp.pspHas(pspPayment(orphan, "CAPTURED", 700, 0, base));
        pair("CAPTURED", 1000, 0, "CAPTURED", 1000, 0);

        Run run = run();

        assertThat(typesFor(run, orphan)).containsExactly(Finding.UNKNOWN_AT_PLATFORM);
        Map<String, Object> row = jdbc.queryForMap("SELECT psp_status, psp_captured_minor, intent_id FROM "
                + "reconciliation_findings WHERE run_id = ? AND psp_payment_id = ?", run.id(), orphan);
        assertThat(row).containsEntry("psp_status", "CAPTURED").containsEntry("psp_captured_minor", 700L);
        assertThat(row.get("intent_id")).isNull();
    }

    @Test
    void anIntentThePspDoesNotKnowIsReportedAsMissingAndOneItStillKnowsIsCompared() {
        String healthy = pair("CAPTURED", 1000, 0, "CAPTURED", 1000, 0);
        String missing = "psp_missing_" + UUID.randomUUID();
        insertIntent("CAPTURED", 1000, 0, missing, base);
        String quiet = "psp_quiet_" + UUID.randomUUID();
        insertIntent("CAPTURED", 1000, 0, quiet, base);
        psp.pspHas(pspPayment(quiet, "CAPTURED", 1000, 0, base.minus(Duration.ofDays(2))));

        Run run = run();

        assertThat(typesFor(run, missing)).containsExactly(Finding.MISSING_AT_PSP);
        assertThat(typesFor(run, quiet)).isEmpty();
        assertThat(typesFor(run, healthy)).isEmpty();
        assertThat(psp.calls("find")).extracting(FakePspClient.Call::pspPaymentId)
                .contains(missing, quiet).doesNotContain(healthy);
    }

    @Test
    void aQuietPaymentWhoseDetailsDisagreeIsStillComparedThroughTheDirectLookup() {
        String quiet = "psp_quiet_" + UUID.randomUUID();
        insertIntent("CAPTURED", 1000, 0, quiet, base);
        psp.pspHas(pspPayment(quiet, "CAPTURED", 400, 0, base.minus(Duration.ofDays(2))));

        Run run = run();

        assertThat(typesFor(run, quiet)).containsExactly(Finding.CAPTURED_AMOUNT_MISMATCH);
    }

    // --- uçuştaki webhook'lar ---

    @Test
    void recentChangesOnEitherSideAreGivenTimeToSettle() {
        Instant recent = Instant.now().minus(Duration.ofMinutes(2));
        String localRecent = "psp_lr_" + UUID.randomUUID();
        insertIntent("AUTHORIZED", 0, 0, localRecent, recent);
        psp.pspHas(pspPayment(localRecent, "CAPTURED", 1000, 0, base));
        String pspRecent = "psp_pr_" + UUID.randomUUID();
        insertIntent("AUTHORIZED", 0, 0, pspRecent, base);
        psp.pspHas(pspPayment(pspRecent, "CAPTURED", 1000, 0, recent));
        String unknownRecent = "psp_ur_" + UUID.randomUUID();
        psp.pspHas(pspPayment(unknownRecent, "CAPTURED", 1000, 0, recent));
        String missingRecent = "psp_mr_" + UUID.randomUUID();
        insertIntent("CAPTURED", 1000, 0, missingRecent, recent);

        Run run = service.run(Trigger.MANUAL, "ops@test", base.minusSeconds(3600), Instant.now().plusSeconds(60));

        assertThat(typesFor(run, localRecent, pspRecent, unknownRecent, missingRecent)).isEmpty();
    }

    // --- takılı işlemler ---

    @Test
    void transactionsOpenLongerThanTheThresholdAreReportedAsStuck() {
        UUID intent = insertIntent("AUTHORIZED", 0, 0, "psp_s_" + UUID.randomUUID(), base);
        Instant old = Instant.now().minus(Duration.ofMinutes(40));
        UUID pending = insertTransaction(intent, "CAPTURE", "PENDING", old, 3, null);
        UUID inProgress = insertTransaction(intent, "VOID", "IN_PROGRESS", old, 1, null);
        UUID unknown = insertTransaction(intent, "REFUND", "UNKNOWN", old, 7, "PSP call failed: timeout");
        insertTransaction(intent, "REFUND", "SUCCEEDED", old, 1, null);
        insertTransaction(intent, "REFUND", "FAILED", old, 1, "rejected");
        UUID fresh = insertTransaction(intent, "CAPTURE", "PENDING", Instant.now().minus(Duration.ofMinutes(2)), 0, null);

        Run run = service.run(Trigger.MANUAL, null, base.minusSeconds(3600), base.plusSeconds(3600));

        List<Map<String, Object>> stuck = jdbc.queryForList("SELECT transaction_id, severity, detail::text AS detail "
                + "FROM reconciliation_findings WHERE run_id = ? AND type = 'STUCK_TRANSACTION'", run.id());
        assertThat(stuck).extracting(row -> row.get("transaction_id"))
                .containsExactlyInAnyOrder(pending, inProgress, unknown).doesNotContain(fresh);
        assertThat(stuck).allSatisfy(row -> assertThat(row.get("severity")).isEqualTo("WARNING"));
        assertThat(stuck.stream().filter(row -> unknown.equals(row.get("transaction_id"))).findFirst().orElseThrow()
                .get("detail").toString()).contains("PSP call failed: timeout").contains("\"attempts\": 7");
        assertThat(run.status()).isEqualTo("DISCREPANCIES");
    }

    // --- PSP verisini kaybetmiş / erişilemeyen PSP ---

    @Test
    void anEmptyPspReportWithPaymentsOnOurSideIsOneProtectiveFindingNotManyFalseOnes() {
        for (int i = 0; i < 6; i++) {
            insertIntent("CAPTURED", 1000, 0, "psp_lost_" + UUID.randomUUID(), base);
        }

        Run run = run();

        assertThat(findings(run)).hasSize(1);
        Map<String, Object> finding = jdbc.queryForMap("SELECT type, severity, detail::text AS detail FROM "
                + "reconciliation_findings WHERE run_id = ?", run.id());
        assertThat(finding).containsEntry("type", Finding.PSP_REPORT_UNAVAILABLE).containsEntry("severity", "CRITICAL");
        assertThat(finding.get("detail").toString()).contains("EMPTY_REPORT").contains("\"intentsInWindow\": 6");
        assertThat(run.status()).isEqualTo("DISCREPANCIES");
        assertThat(psp.calls("find")).hasSize(3);
    }

    @Test
    void anEmptyReportIsNotAProblemWhenThePspStillKnowsOurPaymentsFromEarlier() {
        for (int i = 0; i < 4; i++) {
            String id = "psp_old_" + UUID.randomUUID();
            insertIntent("EXPIRED", 0, 0, id, base);
            psp.pspHas(pspPayment(id, "CREATED", 0, 0, base.minus(Duration.ofDays(3))));
        }

        Run run = run();

        assertThat(run.status()).isEqualTo("OK");
        assertThat(findings(run)).isEmpty();
    }

    @Test
    void aMassOfMissingPaymentsBesidesAWorkingReportCollapsesIntoOneFinding() {
        pair("CAPTURED", 1000, 0, "CAPTURED", 1000, 0);
        for (int i = 0; i < 60; i++) {
            insertIntent("CAPTURED", 1000, 0, "psp_gone_" + UUID.randomUUID(), base);
        }

        Run run = run();

        assertThat(findings(run)).hasSize(1);
        String detail = jdbc.queryForObject("SELECT detail::text FROM reconciliation_findings WHERE run_id = ?",
                String.class, run.id());
        assertThat(detail).contains("MASS_MISSING").contains("\"missingCount\": 60");
    }

    @Test
    void aHandfulOfMissingPaymentsIsReportedIndividually() {
        pair("CAPTURED", 1000, 0, "CAPTURED", 1000, 0);
        for (int i = 0; i < 3; i++) {
            insertIntent("CAPTURED", 1000, 0, "psp_gone_" + UUID.randomUUID(), base);
        }

        Run run = run();

        assertThat(jdbc.queryForList("SELECT type FROM reconciliation_findings WHERE run_id = ?", String.class,
                run.id())).containsExactly(Finding.MISSING_AT_PSP, Finding.MISSING_AT_PSP, Finding.MISSING_AT_PSP);
    }

    @Test
    void anUnreachablePspFailsTheRunButStillReportsWhatWeKnowLocally() {
        UUID intent = insertIntent("AUTHORIZED", 0, 0, "psp_s_" + UUID.randomUUID(), base);
        insertTransaction(intent, "CAPTURE", "UNKNOWN", Instant.now().minus(Duration.ofHours(1)), 9, "timeout");
        psp.reportFails(true);

        Run run = run();

        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.error()).contains("report unavailable");
        assertThat(jdbc.queryForList("SELECT type FROM reconciliation_findings WHERE run_id = ?", String.class,
                run.id())).containsExactlyInAnyOrder(Finding.STUCK_TRANSACTION, Finding.PSP_REPORT_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT detail->>'reason' FROM reconciliation_findings WHERE run_id = ? "
                + "AND type = 'PSP_REPORT_UNAVAILABLE'", String.class, run.id())).isEqualTo("UNREACHABLE");
    }

    // --- yalnızca raporlama ---

    @Test
    void reconciliationNeverChangesPaymentsOrCallsThePspToFixAnything() {
        pair("AUTHORIZED", 0, 0, "CAPTURED", 1000, 0);
        insertIntent("CAPTURED", 1000, 0, "psp_gone_" + UUID.randomUUID(), base);
        psp.pspHas(pspPayment("psp_orphan_" + UUID.randomUUID(), "CAPTURED", 5, 0, base));
        String before = snapshot();
        Integer outboxBefore = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE type <> "
                + "'ReconciliationDiscrepancyDetected'", Integer.class);

        Run run = run();

        assertThat(run.findingsCount()).isPositive();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(psp.calls()).extracting(FakePspClient.Call::operation).containsOnly("list", "find");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE type <> "
                + "'ReconciliationDiscrepancyDetected'", Integer.class)).isEqualTo(outboxBefore);
    }

    // --- outbox alarm olayı ---

    @Test
    void aRunWithFindingsWritesOneAlarmEventWithoutAnyPaymentIdentifiers() {
        String lost = pair("AUTHORIZED", 0, 0, "CAPTURED", 1000, 0);
        pair("CAPTURED", 1000, 0, "CAPTURED", 1000, 0);

        Run run = run();

        List<Map<String, Object>> events = jdbc.queryForList("SELECT aggregate_type, aggregate_id, payload::text AS "
                + "payload FROM outbox_events WHERE type = 'ReconciliationDiscrepancyDetected' AND aggregate_id = ?",
                run.id().toString());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).containsEntry("aggregate_type", "payment");
        var payload = jsonMapper.readTree(events.getFirst().get("payload").toString());
        assertThat(payload.get("eventType").asString()).isEqualTo("ReconciliationDiscrepancyDetected");
        assertThat(payload.get("eventId").asString()).isNotBlank();
        var data = payload.get("data");
        assertThat(data.get("runId").asString()).isEqualTo(run.id().toString());
        assertThat(data.get("status").asString()).isEqualTo("DISCREPANCIES");
        assertThat(data.get("findingsCount").asInt()).isEqualTo(2);
        assertThat(data.get("criticalCount").asInt()).isEqualTo(2);
        assertThat(data.get("types")).extracting(node -> node.asString())
                .containsExactly("CAPTURED_AMOUNT_MISMATCH", "STATUS_MISMATCH");
        assertThat(data.get("windowFrom").asString()).isNotBlank();
        assertThat(events.getFirst().get("payload").toString()).doesNotContain(lost).doesNotContain("psp_")
                .doesNotContain("shipment");
    }

    @Test
    void aCleanRunWritesNoAlarmEvent() {
        pair("CAPTURED", 1000, 0, "CAPTURED", 1000, 0);

        Run run = run();

        assertThat(run.status()).isEqualTo("OK");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class,
                run.id().toString())).isZero();
    }

    @Test
    void anUnreachablePspStillRaisesTheAlarmBecauseTheReportCouldNotBeObtained() {
        psp.reportFails(true);

        Run run = run();

        assertThat(run.status()).isEqualTo("FAILED");
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ?",
                String.class, run.id().toString());
        assertThat(jsonMapper.readTree(payload).get("data").get("types")).extracting(node -> node.asString())
                .containsExactly("PSP_REPORT_UNAVAILABLE");
        assertThat(jsonMapper.readTree(payload).get("data").get("status").asString()).isEqualTo("FAILED");
    }

    @Test
    void theFindingsTheRunRecordAndTheAlarmEventAreWrittenTogether() {
        pair("AUTHORIZED", 0, 0, "CAPTURED", 0, 0);

        Run run = run();

        assertThat(run.findingsCount()).isEqualTo(findings(run).size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND type = "
                + "'ReconciliationDiscrepancyDetected'", Integer.class, run.id().toString())).isEqualTo(1);
    }

    // --- koşu yaşam döngüsü ---

    @Test
    void theNextRunStartsFromTheLastCompletedWindowMinusTheOverlap() {
        jdbc.update("UPDATE reconciliation_runs SET status = 'FAILED' WHERE status IN ('OK', 'DISCREPANCIES')");
        Instant end = Instant.now().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO reconciliation_runs (trigger_source, status, finished_at, window_from, window_to) "
                + "VALUES ('SCHEDULED', 'OK', now(), ?, ?)", Timestamp.from(end.minus(Duration.ofHours(1))),
                Timestamp.from(end));

        Run started = service.start(Trigger.SCHEDULED, null, null, null);

        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(started.windowFrom()).isEqualTo(end.minus(Duration.ofHours(1)));
        assertThat(started.windowTo()).isAfter(end).isBeforeOrEqualTo(Instant.now());
        assertThat(started.trigger()).isEqualTo("SCHEDULED");
        service.execute(started);
    }

    @Test
    void failedRunsDoNotAdvanceTheWindow() {
        Instant far = Instant.now().plus(Duration.ofDays(400)).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO reconciliation_runs (trigger_source, status, finished_at, window_from, window_to) "
                + "VALUES ('SCHEDULED', 'FAILED', now(), ?, ?)", Timestamp.from(far.minusSeconds(60)),
                Timestamp.from(far));

        assertThat(repository.lastCompletedWindowEnd()).hasValueSatisfying(end -> assertThat(end).isBefore(far));
    }

    @Test
    void onlyOneRunCanBeActiveAtATime() {
        Run active = service.start(Trigger.MANUAL, "a", base.minusSeconds(3600), base.plusSeconds(3600));

        assertThatThrownBy(() -> service.start(Trigger.SCHEDULED, null, base.minusSeconds(3600), base.plusSeconds(3600)))
                .isInstanceOf(RunAlreadyActiveException.class);

        service.execute(active);
        assertThat(service.run(Trigger.MANUAL, "b", base.minusSeconds(3600), base.plusSeconds(3600)).status())
                .isEqualTo("OK");
    }

    @Test
    void aStuckRunIsFailedAfterTheStaleThresholdButAFreshOneIsLeftAlone() {
        Run stuck = service.start(Trigger.SCHEDULED, null, base.minusSeconds(3600), base.plusSeconds(3600));
        jdbc.update("UPDATE reconciliation_runs SET started_at = now() - interval '1 hour' WHERE id = ?", stuck.id());

        assertThat(service.failStaleRuns()).isEqualTo(1);
        assertThat(repository.findRun(stuck.id()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(repository.findRun(stuck.id()).orElseThrow().error()).contains("did not finish");

        Run fresh = service.start(Trigger.SCHEDULED, null, base.minusSeconds(3600), base.plusSeconds(3600));
        assertThat(service.failStaleRuns()).isZero();
        assertThat(repository.findRun(fresh.id()).orElseThrow().status()).isEqualTo("RUNNING");
    }

    // --- yardımcılar ---

    private Run run() {
        return service.run(Trigger.MANUAL, "ops@test", base.minusSeconds(3600), base.plusSeconds(3600));
    }

    private List<String> findings(Run run) {
        return jdbc.queryForList("SELECT type FROM reconciliation_findings WHERE run_id = ?", String.class, run.id());
    }

    private List<String> typesFor(Run run, String... pspPaymentIds) {
        return jdbc.queryForList("SELECT type FROM reconciliation_findings WHERE run_id = ? AND psp_payment_id = ANY(?) "
                + "ORDER BY id", String.class, run.id(), (Object) pspPaymentIds);
    }

    /** Aynı psp kimliğiyle hem niyet hem PSP kaydı ekler, PSP kimliğini döner. İkisi de {@code base} anında güncellendi. */
    private String pair(String localStatus, long localCaptured, long localRefunded, String pspStatus,
                        long pspCaptured, long pspRefunded) {
        String id = "psp_" + UUID.randomUUID();
        insertIntent(localStatus, localCaptured, localRefunded, id, base);
        psp.pspHas(pspPayment(id, pspStatus, pspCaptured, pspRefunded, base));
        return id;
    }

    private static PspPayment pspPayment(String id, String status, long captured, long refunded, Instant updatedAt) {
        return new PspPayment(id, "ref-" + id, status, 1000, captured, refunded, updatedAt, updatedAt);
    }

    private UUID insertIntent(String status, long captured, long refunded, String pspPaymentId, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_intents (id, idempotency_key, request_hash, shipment_id, amount_minor, "
                        + "currency, status, psp_payment_id, checkout_url, captured_minor, refunded_minor, "
                        + "expires_at, created_at, updated_at) VALUES (?, ?, 'h', ?, 1000, 'TRY', ?, ?, 'http://x', "
                        + "?, ?, ?, ?, ?)", id, UUID.randomUUID().toString(), UUID.randomUUID(), status, pspPaymentId,
                captured, refunded, Timestamp.from(updatedAt.plusSeconds(900)), Timestamp.from(updatedAt),
                Timestamp.from(updatedAt));
        return id;
    }

    private UUID insertTransaction(UUID intentId, String type, String status, Instant updatedAt, int attempts,
                                   String lastError) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_transactions (id, intent_id, type, command_key, request_hash, amount_minor, "
                        + "status, attempts, last_error, created_at, updated_at) VALUES (?, ?, ?, ?, 'h', 100, ?, ?, "
                        + "?, ?, ?)", id, intentId, type, UUID.randomUUID().toString(), status, attempts, lastError,
                Timestamp.from(updatedAt), Timestamp.from(updatedAt));
        return id;
    }

    private String snapshot() {
        return jdbc.queryForList("SELECT id::text || status || captured_minor || refunded_minor || version || "
                + "updated_at::text FROM payment_intents ORDER BY id", String.class).toString()
                + jdbc.queryForList("SELECT id::text || status || attempts || updated_at::text FROM "
                + "payment_transactions ORDER BY id", String.class);
    }
}
