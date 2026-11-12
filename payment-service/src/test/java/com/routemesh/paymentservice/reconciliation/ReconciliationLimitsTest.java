package com.routemesh.paymentservice.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.routemesh.paymentservice.FakePspClient;
import com.routemesh.paymentservice.TestPspConfiguration;
import com.routemesh.paymentservice.TestcontainersConfiguration;
import com.routemesh.paymentservice.psp.PspClient.PspPayment;
import com.routemesh.paymentservice.reconciliation.ReconciliationRepository.Run;
import com.routemesh.paymentservice.reconciliation.ReconciliationService.Trigger;

/** Küçük sayfa ve üst sınır ayarlarıyla ayrı bir bağlam: sayfalama, bulgu üst sınırı ve ilk koşu penceresi. */
@Import({TestcontainersConfiguration.class, TestPspConfiguration.class})
@SpringBootTest(properties = {
        "spring.grpc.server.port=0",
        "routemesh.payment.reconciliation.scheduler-enabled=false",
        "routemesh.payment.reconciliation.page-size=2",
        "routemesh.payment.reconciliation.max-findings-per-run=3",
        "routemesh.payment.reconciliation.max-lookups=2",
        "routemesh.payment.reconciliation.missing-flood-threshold=100",
        "routemesh.payment.reconciliation.initial-lookback=6h"})
class ReconciliationLimitsTest {

    @Autowired
    private ReconciliationService service;

    @Autowired
    private FakePspClient psp;

    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicInteger WINDOWS = new AtomicInteger();

    private final Instant base = Instant.now().minus(Duration.ofDays(5))
            .minus(Duration.ofHours(3L * WINDOWS.incrementAndGet())).truncatedTo(ChronoUnit.MICROS);

    @Test
    void theFirstRunLooksBackOnlyAsFarAsTheInitialLookback() {
        jdbc.update("UPDATE reconciliation_runs SET status = 'FAILED' WHERE status IN ('OK', 'DISCREPANCIES')");

        Run started = service.start(Trigger.SCHEDULED, null, null, null);

        assertThat(Duration.between(started.windowFrom(), started.windowTo())).isEqualTo(Duration.ofHours(6));
        service.execute(started);
    }

    @Test
    void reportAndIntentPagesAreWalkedUntilTheEnd() {
        psp.reset();
        for (int i = 0; i < 5; i++) {
            String id = "psp_page_" + UUID.randomUUID();
            insertIntent("CAPTURED", 1000, 0, id);
            psp.pspHas(pspPayment(id, "CAPTURED", i == 4 ? 123 : 1000));
        }

        Run run = service.run(Trigger.MANUAL, null, base.minusSeconds(3600), base.plusSeconds(3600));

        assertThat(run.pspPaymentsSeen()).isEqualTo(5);
        assertThat(run.intentsChecked()).isEqualTo(5);
        assertThat(psp.calls("list")).hasSize(3);
        assertThat(types(run)).containsExactly(Finding.CAPTURED_AMOUNT_MISMATCH);
    }

    @Test
    void tooManyFindingsAreCutOffWithATruncationNotice() {
        psp.reset();
        for (int i = 0; i < 6; i++) {
            String id = "psp_cut_" + UUID.randomUUID();
            insertIntent("AUTHORIZED", 0, 0, id);
            psp.pspHas(pspPayment(id, "AUTHORIZED", 55));
        }

        Run run = service.run(Trigger.MANUAL, null, base.minusSeconds(3600), base.plusSeconds(3600));

        List<String> types = types(run);
        assertThat(types).hasSize(4).endsWith(Finding.TRUNCATED);
        assertThat(types.subList(0, 3)).containsOnly(Finding.CAPTURED_AMOUNT_MISMATCH);
        assertThat(jdbc.queryForObject("SELECT detail->>'droppedFindings' FROM reconciliation_findings "
                + "WHERE run_id = ? AND type = 'TRUNCATED'", String.class, run.id())).isEqualTo("3");
        assertThat(run.status()).isEqualTo("DISCREPANCIES");
    }

    @Test
    void theNumberOfDirectLookupsPerRunIsCapped() {
        psp.reset();
        String present = "psp_present_" + UUID.randomUUID();
        insertIntent("CAPTURED", 1000, 0, present);
        psp.pspHas(pspPayment(present, "CAPTURED", 1000));
        for (int i = 0; i < 5; i++) {
            insertIntent("CAPTURED", 1000, 0, "psp_gone_" + UUID.randomUUID());
        }

        Run run = service.run(Trigger.MANUAL, null, base.minusSeconds(3600), base.plusSeconds(3600));

        assertThat(psp.calls("find")).hasSize(2);
        assertThat(types(run)).containsExactly(Finding.MISSING_AT_PSP, Finding.MISSING_AT_PSP, Finding.TRUNCATED);
        assertThat(jdbc.queryForObject("SELECT detail->>'skippedLookups' FROM reconciliation_findings "
                + "WHERE run_id = ? AND type = 'TRUNCATED'", String.class, run.id())).isEqualTo("3");
    }

    private List<String> types(Run run) {
        return jdbc.queryForList("SELECT type FROM reconciliation_findings WHERE run_id = ? ORDER BY id",
                String.class, run.id());
    }

    private PspPayment pspPayment(String id, String status, long captured) {
        return new PspPayment(id, "ref-" + id, status, 1000, captured, 0, base, base);
    }

    private void insertIntent(String status, long captured, long refunded, String pspPaymentId) {
        jdbc.update("INSERT INTO payment_intents (idempotency_key, request_hash, shipment_id, amount_minor, "
                        + "currency, status, psp_payment_id, checkout_url, captured_minor, refunded_minor, "
                        + "expires_at, created_at, updated_at) VALUES (?, 'h', ?, 1000, 'TRY', ?, ?, 'http://x', "
                        + "?, ?, ?, ?, ?)", UUID.randomUUID().toString(), UUID.randomUUID(), status, pspPaymentId,
                captured, refunded, Timestamp.from(base.plusSeconds(900)), Timestamp.from(base), Timestamp.from(base));
    }
}
