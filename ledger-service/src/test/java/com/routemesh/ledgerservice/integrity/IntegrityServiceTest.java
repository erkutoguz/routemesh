package com.routemesh.ledgerservice.integrity;

import static com.routemesh.ledgerservice.integrity.IntegrityTestSupport.ESCROW;
import static com.routemesh.ledgerservice.integrity.IntegrityTestSupport.PSP;
import static com.routemesh.ledgerservice.integrity.IntegrityTestSupport.credit;
import static com.routemesh.ledgerservice.integrity.IntegrityTestSupport.debit;
import static com.routemesh.ledgerservice.integrity.IntegrityTestSupport.raw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.ledgerservice.TestcontainersConfiguration;
import com.routemesh.ledgerservice.integrity.IntegrityRepository.Checkpoint;
import com.routemesh.ledgerservice.integrity.IntegrityRepository.Run;
import com.routemesh.ledgerservice.integrity.IntegrityService.Mode;
import com.routemesh.ledgerservice.integrity.IntegrityService.RunAlreadyActiveException;
import com.routemesh.ledgerservice.integrity.IntegrityService.Trigger;
import com.routemesh.ledgerservice.repository.LedgerRepository;
import com.routemesh.ledgerservice.service.LedgerService;

import io.micrometer.core.instrument.MeterRegistry;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.grpc.server.port=0",
        "routemesh.ledger.integrity.scheduler-enabled=false",
        "routemesh.ledger.integrity.chunk-entries=6",
        "routemesh.ledger.integrity.lock-timeout=20s"})
class IntegrityServiceTest {

    @Autowired
    private IntegrityService service;

    @Autowired
    private IntegrityRepository repository;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meters;

    private IntegrityTestSupport ledgerSupport;

    @BeforeEach
    void reset() {
        ledgerSupport = new IntegrityTestSupport(jdbc, tx, ledger);
        ledgerSupport.reset();
    }

    // --- INCREMENTAL, temiz defter ---

    @Test
    void anEmptyLedgerProducesAnOkRunWithoutACheckpoint() {
        Run run = service.run(Mode.INCREMENTAL, Trigger.MANUAL, "ops");

        assertThat(run.status()).isEqualTo("OK");
        assertThat(run.entriesScanned()).isZero();
        assertThat(run.requestedBy()).isEqualTo("ops");
        assertThat(repository.latestCheckpoint()).isEmpty();
    }

    @Test
    void aHealthyLedgerIsCheckpointedAndEachRunOnlyScansWhatIsNew() {
        String merchant = ledgerSupport.wallet("MERCHANT_WALLET");
        String courier = ledgerSupport.wallet("COURIER_WALLET");
        UUID first = UUID.randomUUID();
        ledgerSupport.capture(first, 1001);
        ledgerSupport.settle(first, 1001, merchant, courier);
        long entriesAfterFirst = count("ledger_entries");

        Run one = service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);

        assertThat(one.status()).isEqualTo("OK");
        assertThat(one.entriesScanned()).isEqualTo(entriesAfterFirst).isEqualTo(6);
        Checkpoint cp1 = repository.latestCheckpoint().orElseThrow();
        assertThat(cp1.entryCount()).isEqualTo(6);
        assertThat(cp1.totalDebitMinor()).isEqualTo(cp1.totalCreditMinor()).isEqualTo(2002);
        assertThat(cp1.chainHash()).hasSize(64).isNotEqualTo(ChainHasher.GENESIS);
        assertThat(one.findingsCount()).isZero();

        UUID second = UUID.randomUUID();
        ledgerSupport.capture(second, 500);
        Run two = service.run(Mode.INCREMENTAL, Trigger.SCHEDULED, null);

        assertThat(two.entriesScanned()).isEqualTo(2);
        assertThat(two.fromEntryId()).isEqualTo(cp1.lastEntryId() + 1);
        Checkpoint cp2 = repository.latestCheckpoint().orElseThrow();
        assertThat(cp2.entryCount()).isEqualTo(8);
        assertThat(cp2.totalDebitMinor()).isEqualTo(2502);
        assertThat(cp2.chainHash()).isNotEqualTo(cp1.chainHash());

        Run nothing = service.run(Mode.INCREMENTAL, Trigger.SCHEDULED, null);
        assertThat(nothing.entriesScanned()).isZero();
        assertThat(repository.checkpointsAscending()).hasSize(2);
    }

    @Test
    void theBalanceCacheTracksWalletsAndPerShipmentEscrowAndDropsZeroRows() {
        String merchant = ledgerSupport.wallet("MERCHANT_WALLET");
        String courier = ledgerSupport.wallet("COURIER_WALLET");
        UUID settled = UUID.randomUUID();
        UUID open = UUID.randomUUID();
        ledgerSupport.capture(settled, 1000);
        ledgerSupport.settle(settled, 1000, merchant, courier);
        ledgerSupport.capture(open, 700);

        service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);

        Map<String, Long> cache = new java.util.HashMap<>();
        repository.cacheRows().forEach(r -> cache.put(r.accountCode() + "/" + r.scopeShipmentId(), r.netDebit()));
        assertThat(cache).containsEntry(ESCROW + "/" + open, -700L)
                .containsEntry(merchant + "/" + new UUID(0, 0), -750L)
                .containsEntry(courier + "/" + new UUID(0, 0), -180L)
                .containsEntry(PSP + "/" + new UUID(0, 0), 1700L)
                .doesNotContainKey(ESCROW + "/" + settled);
    }

    @Test
    void largeDeltasAreCheckpointedInChunksThatNeverSplitATransaction() {
        String merchant = ledgerSupport.wallet("MERCHANT_WALLET");
        String courier = ledgerSupport.wallet("COURIER_WALLET");
        for (int i = 0; i < 5; i++) {
            UUID shipment = UUID.randomUUID();
            ledgerSupport.capture(shipment, 1000 + i);
            ledgerSupport.settle(shipment, 1000 + i, merchant, courier);
        }

        Run run = service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);

        assertThat(run.status()).isEqualTo("OK");
        assertThat(run.entriesScanned()).isEqualTo(30);
        List<Checkpoint> checkpoints = repository.checkpointsAscending();
        assertThat(checkpoints.size()).isGreaterThan(2);
        for (Checkpoint checkpoint : checkpoints) {
            Integer split = jdbc.queryForObject("SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries "
                    + "GROUP BY transaction_id HAVING min(id) <= ? AND max(id) > ?) s", Integer.class,
                    checkpoint.lastEntryId(), checkpoint.lastEntryId());
            assertThat(split).as("transactions split by checkpoint %s", checkpoint.id()).isZero();
        }
        assertThat(checkpoints.getLast().entryCount()).isEqualTo(30);
        assertThat(service.run(Mode.FULL, Trigger.MANUAL, null).status()).isEqualTo("OK");
    }

    // --- metrikler ---

    @Test
    void runsFindingsAndTheCheckpointLagAreExposedAsMeters() {
        double violations = runs("INCREMENTAL", "VIOLATIONS");
        double unbalanced = findings(Finding.UNBALANCED_TRANSACTION);
        double fullViolations = runs("FULL", "VIOLATIONS");
        double ok = runs("INCREMENTAL", "OK");
        UUID shipment = UUID.randomUUID();
        ledgerSupport.capture(shipment, 700);
        assertThat(lag()).isEqualTo(count("ledger_entries") - repository.latestCheckpoint()
                .map(Checkpoint::lastEntryId).orElse(0L)).isEqualTo(2);

        service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);

        assertThat(lag()).isZero();
        assertThat(runs("INCREMENTAL", "OK") - ok).isEqualTo(1);

        ledgerSupport.rawTransaction("BROKEN", null, null, raw(PSP, "DEBIT", 900), raw(ESCROW, "CREDIT", 500));
        assertThat(lag()).isEqualTo(2);
        service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);
        service.run(Mode.FULL, Trigger.MANUAL, null);

        assertThat(runs("INCREMENTAL", "VIOLATIONS") - violations).isEqualTo(1);
        assertThat(findings(Finding.UNBALANCED_TRANSACTION) - unbalanced).isGreaterThanOrEqualTo(1);
        assertThat(runs("FULL", "VIOLATIONS") - fullViolations).isEqualTo(1);
    }

    private double runs(String mode, String status) {
        var counter = meters.find("routemesh.ledger.integrity.runs").tags("mode", mode, "status", status).counter();
        return counter == null ? 0 : counter.count();
    }

    private double findings(String type) {
        var counter = meters.find("routemesh.ledger.integrity.findings").tag("type", type).counter();
        return counter == null ? 0 : counter.count();
    }

    private double lag() {
        return meters.get("routemesh.ledger.integrity.checkpoint.lag.entries").gauge().value();
    }

    // --- INCREMENTAL, ihlaller ---

    @Test
    void anUnbalancedTransactionAndTheTotalsMismatchAreFoundAndAnAlarmEventIsWritten() {
        UUID transaction = ledgerSupport.rawTransaction("BROKEN", null, null,
                raw(PSP, "DEBIT", 900), raw(ESCROW, "CREDIT", 500));

        Run run = service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);

        assertThat(run.status()).isEqualTo("VIOLATIONS");
        Map<String, Integer> types = repository.findingTypeCounts(run.id());
        assertThat(types).containsEntry(Finding.UNBALANCED_TRANSACTION, 1).containsEntry(Finding.TOTALS_MISMATCH, 1);
        assertThat(repository.listFindings(run.id(), Finding.UNBALANCED_TRANSACTION, null, 10).getFirst())
                .satisfies(f -> {
                    assertThat(f.transactionId()).isEqualTo(transaction);
                    assertThat(f.actualMinor()).isEqualTo(400);
                    assertThat(f.severity()).isEqualTo("CRITICAL");
                });
        Map<String, Object> outbox = jdbc.queryForMap("SELECT type, aggregate_type, aggregate_id, payload::text AS payload "
                + "FROM outbox_events WHERE type = 'LedgerIntegrityViolationDetected'");
        assertThat(outbox).containsEntry("aggregate_id", run.id().toString());
        assertThat((String) outbox.get("payload")).contains("UNBALANCED_TRANSACTION").doesNotContain("shipment");
    }

    @Test
    void aLiabilityThatGoesNegativeIsReportedPerWalletAndPerShipmentEscrow() {
        String wallet = ledgerSupport.wallet("MERCHANT_WALLET");
        UUID shipment = UUID.randomUUID();
        ledgerSupport.rawTransaction("PAYOUT", null, null, raw(wallet, "DEBIT", 300), raw(PSP, "CREDIT", 300));
        ledgerSupport.rawTransaction("SETTLEMENT", shipment, null, raw(ESCROW, "DEBIT", 400), raw(PSP, "CREDIT", 400));

        Run run = service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);

        assertThat(run.status()).isEqualTo("VIOLATIONS");
        assertThat(repository.listFindings(run.id(), Finding.NEGATIVE_BALANCE, null, 10))
                .extracting(f -> f.accountCode() + "/" + f.shipmentId() + "/" + f.actualMinor())
                .containsExactlyInAnyOrder(wallet + "/null/300", ESCROW + "/" + shipment + "/400");
        assertThat(repository.negativeLiabilities(10)).hasSize(2);
    }

    @Test
    void healthyAssetAndRevenueBalancesAreNotFlagged() {
        UUID shipment = UUID.randomUUID();
        ledgerSupport.rawTransaction("ODD", null, null, raw(PSP, "CREDIT", 50), raw("EXPENSE:LOST_COMPENSATION", "DEBIT", 50));

        assertThat(service.run(Mode.INCREMENTAL, Trigger.MANUAL, null).status()).isEqualTo("OK");
        assertThat(shipment).isNotNull();
    }

    @Test
    void aReversalThatDoesNotMirrorTheOriginalIsReported() {
        UUID shipment = UUID.randomUUID();
        UUID original = ledgerSupport.capture(shipment, 1000);
        ledgerSupport.rawTransaction("ESCROW_REVERSAL", shipment, original,
                raw(PSP, "CREDIT", 900), raw(ESCROW, "DEBIT", 900));

        Run run = service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);

        assertThat(repository.findingTypeCounts(run.id())).containsEntry(Finding.REVERSAL_MISMATCH, 1);
    }

    @Test
    void aProperReversalIsFine() {
        UUID shipment = UUID.randomUUID();
        UUID original = ledgerSupport.capture(shipment, 1000);
        ledger.post(new com.routemesh.ledgerservice.domain.PostTransactionCommand(UUID.randomUUID().toString(),
                "ESCROW_REVERSAL", shipment, original, List.of(credit(PSP, 1000), debit(ESCROW, 1000))));

        assertThat(service.run(Mode.INCREMENTAL, Trigger.MANUAL, null).status()).isEqualTo("OK");
    }

    // --- FULL ---

    @Test
    void aFullAuditOfAHealthyLedgerIsOkAndLeavesCheckpointsAndCacheUntouched() {
        String merchant = ledgerSupport.wallet("MERCHANT_WALLET");
        String courier = ledgerSupport.wallet("COURIER_WALLET");
        UUID shipment = UUID.randomUUID();
        ledgerSupport.capture(shipment, 1001);
        ledgerSupport.settle(shipment, 1001, merchant, courier);
        service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);
        long checkpoints = count("ledger_checkpoints");
        long cacheRows = count("ledger_balance_cache");

        Run full = service.run(Mode.FULL, Trigger.MANUAL, null);

        assertThat(full.status()).isEqualTo("OK");
        assertThat(full.entriesScanned()).isEqualTo(6);
        assertThat(count("ledger_checkpoints")).isEqualTo(checkpoints);
        assertThat(count("ledger_balance_cache")).isEqualTo(cacheRows);
    }

    @Test
    void aFullAuditCatchesABalancedRewriteOfOldHistory() {
        UUID shipment = UUID.randomUUID();
        UUID tampered = ledgerSupport.capture(shipment, 1000);
        ledgerSupport.capture(UUID.randomUUID(), 300);
        service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);
        assertThat(service.run(Mode.FULL, Trigger.MANUAL, null).status()).isEqualTo("OK");

        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_immutable");
        try {
            jdbc.update("UPDATE ledger_entries SET amount_minor = 1500 WHERE transaction_id = ?", tampered);
        } finally {
            jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_immutable");
        }

        Run full = service.run(Mode.FULL, Trigger.MANUAL, null);

        assertThat(full.status()).isEqualTo("VIOLATIONS");
        Map<String, Integer> types = repository.findingTypeCounts(full.id());
        assertThat(types).containsKey(Finding.HISTORY_TAMPERED).containsKey(Finding.CACHE_DRIFT);
        assertThat(types).doesNotContainKey(Finding.UNBALANCED_TRANSACTION);
        var finding = repository.listFindings(full.id(), Finding.HISTORY_TAMPERED, null, 10).getFirst();
        assertThat(finding.detail()).contains("storedHash").contains("recomputedHash");
    }

    @Test
    void aFullAuditCatchesACacheThatDriftedFromTheEntries() {
        ledgerSupport.capture(UUID.randomUUID(), 1000);
        service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);
        jdbc.update("UPDATE ledger_balance_cache SET net_debit_minor = net_debit_minor + 5 WHERE net_debit_minor > 0");

        Run full = service.run(Mode.FULL, Trigger.MANUAL, null);

        assertThat(repository.findingTypeCounts(full.id())).containsOnlyKeys(Finding.CACHE_DRIFT);
    }

    @Test
    void aFullAuditWithoutCheckpointsHasNothingToCompare() {
        ledgerSupport.capture(UUID.randomUUID(), 1000);

        Run full = service.run(Mode.FULL, Trigger.MANUAL, null);

        assertThat(full.status()).isEqualTo("OK");
        assertThat(full.entriesScanned()).isZero();
    }

    // --- tek koşu garantisi ve takılı kalan koşular ---

    @Test
    void onlyOneRunPerModeCanBeActiveAndOtherModesAreIndependent() {
        Run active = service.start(Mode.FULL, Trigger.MANUAL, null);

        assertThatThrownBy(() -> service.start(Mode.FULL, Trigger.SCHEDULED, null))
                .isInstanceOf(RunAlreadyActiveException.class);
        Run incremental = service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);
        assertThat(incremental.status()).isEqualTo("OK");

        service.execute(active);
        assertThat(service.start(Mode.FULL, Trigger.MANUAL, null).status()).isEqualTo("RUNNING");
    }

    @Test
    void aStuckRunIsMarkedFailedAfterTheStaleThreshold() {
        Run stuck = service.start(Mode.INCREMENTAL, Trigger.SCHEDULED, null);
        Run fresh = service.start(Mode.FULL, Trigger.SCHEDULED, null);
        jdbc.update("UPDATE integrity_runs SET started_at = now() - interval '1 hour' WHERE id = ?", stuck.id());

        assertThat(service.failStaleRuns()).isEqualTo(1);

        assertThat(repository.findRun(stuck.id()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(repository.findRun(stuck.id()).orElseThrow().error()).contains("did not finish");
        assertThat(repository.findRun(fresh.id()).orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(service.start(Mode.INCREMENTAL, Trigger.SCHEDULED, null)).isNotNull();
    }

    // --- sırasız commit boşluğu ---

    @Test
    void aPostThatIsStillInFlightIsNeverSkippedByTheCheckpointEvenWhenLaterPostsCommittedFirst() throws Exception {
        // A: önce id alır ama commit etmez (ortak kapının paylaşımlı kilidini tutar)
        Connection inFlight = dataSource.getConnection();
        inFlight.setAutoCommit(false);
        UUID slowTransaction = UUID.randomUUID();
        try (PreparedStatement lock = inFlight.prepareStatement(
                "SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))")) {
            lock.setString(1, LedgerRepository.ENTRIES_GATE);
            lock.execute();
        }
        jdbc.update("INSERT INTO accounts (code, type, currency) SELECT 'LIABILITY:SLOW_WALLET', 'LIABILITY', 'TRY' "
                + "WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE code = 'LIABILITY:SLOW_WALLET')");
        try (PreparedStatement insertTx = inFlight.prepareStatement("INSERT INTO journal_transactions (id, "
                + "idempotency_key, request_hash, transaction_type) VALUES (?, ?, 'slow', 'SLOW')");
             PreparedStatement insertEntry = inFlight.prepareStatement("INSERT INTO ledger_entries (transaction_id, "
                + "account_id, direction, amount_minor) SELECT ?, id, ?, 100 FROM accounts WHERE code = ?")) {
            insertTx.setObject(1, slowTransaction);
            insertTx.setString(2, UUID.randomUUID().toString());
            insertTx.execute();
            insertEntry.setObject(1, slowTransaction);
            insertEntry.setString(2, "CREDIT");
            insertEntry.setString(3, "LIABILITY:SLOW_WALLET");
            insertEntry.execute();
            insertEntry.setString(2, "DEBIT");
            insertEntry.setString(3, PSP);
            insertEntry.execute();
        }
        // B: A'dan sonra id alan ama önce commit eden normal bir gönderim (paylaşımlı kilit çakışmaz)
        ledgerSupport.capture(UUID.randomUUID(), 250);

        CompletableFuture<Run> checkpointRun = CompletableFuture.supplyAsync(
                () -> service.run(Mode.INCREMENTAL, Trigger.MANUAL, null));
        Thread.sleep(1500);
        assertThat(checkpointRun).as("checkpoint must wait for the in-flight post").isNotDone();

        inFlight.commit();
        inFlight.close();
        Run run = checkpointRun.get(30, TimeUnit.SECONDS);

        assertThat(run.status()).isEqualTo("OK");
        assertThat(run.entriesScanned()).isEqualTo(4);
        assertThat(repository.latestCheckpoint().orElseThrow().entryCount()).isEqualTo(4);
        assertThat(repository.cacheRows()).anySatisfy(row -> assertThat(row.accountCode())
                .isEqualTo("LIABILITY:SLOW_WALLET"));
        assertThat(service.run(Mode.FULL, Trigger.MANUAL, null).status()).isEqualTo("OK");
    }

    @Test
    void postsRunConcurrentlyWithEachOtherAndWaitOnlyWhileTheCheckpointHoldsTheGate() throws Exception {
        List<CompletableFuture<?>> posts = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            posts.add(CompletableFuture.runAsync(() -> ledgerSupport.capture(UUID.randomUUID(), 100)));
        }
        CompletableFuture<Run> run = CompletableFuture.supplyAsync(
                () -> service.run(Mode.INCREMENTAL, Trigger.MANUAL, null));
        CompletableFuture.allOf(posts.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        run.get(30, TimeUnit.SECONDS);

        service.run(Mode.INCREMENTAL, Trigger.MANUAL, null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.latestCheckpoint().orElseThrow().entryCount()).isEqualTo(16));
        assertThat(service.run(Mode.FULL, Trigger.MANUAL, null).status()).isEqualTo("OK");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
