package com.routemesh.ledgerservice.integrity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.contracts.events.EventEnvelope;
import com.routemesh.contracts.events.LedgerIntegrityViolationDetectedEvent;
import com.routemesh.ledgerservice.integrity.IntegrityRepository.BalanceRow;
import com.routemesh.ledgerservice.integrity.IntegrityRepository.Checkpoint;
import com.routemesh.ledgerservice.integrity.IntegrityRepository.RangeTotals;
import com.routemesh.ledgerservice.integrity.IntegrityRepository.Run;
import com.routemesh.ledgerservice.integrity.IntegrityRepository.Unbalanced;
import com.routemesh.ledgerservice.repository.LedgerRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Defter bütünlük denetimi. INCREMENTAL: son checkpoint'ten sonraki kayıtları, ortak kapının özel kilidi altında
 * (bekleyen gönderimler biter, yenileri bekler) tam işlemler halinde tarar; böylece sırasız commit boşluğu olmaz.
 * FULL: kilitsiz, {@code REPEATABLE READ} anlık görüntüde tüm geçmişi sıfırdan yeniden hesaplayıp checkpoint'lerle
 * karşılaştırır.
 */
@Service
public class IntegrityService {

    public enum Mode { INCREMENTAL, FULL }

    public enum Trigger { SCHEDULED, MANUAL }

    public static class RunAlreadyActiveException extends RuntimeException {
        public RunAlreadyActiveException(Mode mode) {
            super("a " + mode + " integrity run is already in progress");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(IntegrityService.class);
    private static final String AGGREGATE_TYPE = "ledger";
    private static final String LIABILITY = "LIABILITY";

    private record Chunk(long fromExclusive, long upper, long entries, List<Finding> findings) {
    }

    private record Audit(long upper, long entries, List<Finding> findings) {
    }

    private final IntegrityRepository repository;
    private final LedgerRepository ledgerRepository;
    private final IntegrityProperties properties;
    private final TransactionTemplate tx;
    private final TransactionTemplate snapshot;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meters;

    public IntegrityService(IntegrityRepository repository, LedgerRepository ledgerRepository,
                            IntegrityProperties properties, PlatformTransactionManager transactionManager,
                            JsonMapper jsonMapper, MeterRegistry meters) {
        this.meters = meters;
        // Her scrape'te iki küçük indeksli sorgu; checkpoint'siz kalan kayıt sayısı büyüyorsa denetim geride demektir
        Gauge.builder("routemesh.ledger.integrity.checkpoint.lag.entries", repository, r -> {
            try {
                return r.checkpointLag();
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        }).register(meters);
        this.repository = repository;
        this.ledgerRepository = ledgerRepository;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.jsonMapper = jsonMapper;
    }

    /** Koşu kaydını açar; aynı modda çalışan bir koşu varsa {@link RunAlreadyActiveException}. */
    Run start(Mode mode, Trigger trigger, String requestedBy) {
        try {
            return repository.insertRun(mode.name(), trigger.name(), requestedBy);
        } catch (DuplicateKeyException e) {
            throw new RunAlreadyActiveException(mode);
        }
    }

    public Run run(Mode mode, Trigger trigger, String requestedBy) {
        return execute(start(mode, trigger, requestedBy));
    }

    /** Başlatılmış bir koşuyu eşzamanlı yürütür ve son halini döner. Hata koşuyu {@code FAILED} yapar, fırlatmaz. */
    Run execute(Run run) {
        UUID id = run.id();
        Long from = null;
        Long to = null;
        long scanned = 0;
        List<Finding> all = new ArrayList<>();
        try {
            if ("FULL".equals(run.mode())) {
                Audit audit = snapshot.execute(status -> audit());
                from = audit.upper() > 0 ? 1L : null;
                to = audit.upper() > 0 ? audit.upper() : null;
                scanned = audit.entries();
                all.addAll(audit.findings());
                tx.executeWithoutResult(status -> repository.insertFindings(id, audit.findings()));
            } else {
                while (true) {
                    Chunk chunk = tx.execute(status -> processChunk(id));
                    if (chunk == null) {
                        break;
                    }
                    from = from == null ? chunk.fromExclusive() + 1 : from;
                    to = chunk.upper();
                    scanned += chunk.entries();
                    all.addAll(chunk.findings());
                }
            }
        } catch (RuntimeException e) {
            log.error("LEDGER_INTEGRITY_RUN_FAILED run={} mode={}", id, run.mode(), e);
            String message = String.valueOf(e.getMessage());
            repository.finishRun(id, "FAILED", from, to, scanned, all.size(),
                    message.length() > 500 ? message.substring(0, 500) : message);
            recordRun(run.mode(), "FAILED", all);
            return repository.findRun(id).orElseThrow();
        }
        String status = all.isEmpty() ? "OK" : "VIOLATIONS";
        Long finalFrom = from;
        Long finalTo = to;
        long finalScanned = scanned;
        tx.executeWithoutResult(s -> {
            repository.finishRun(id, status, finalFrom, finalTo, finalScanned, all.size(), null);
            if (!all.isEmpty()) {
                publishViolation(run, all);
            }
        });
        recordRun(run.mode(), status, all);
        if (!all.isEmpty()) {
            log.error("LEDGER_INTEGRITY_VIOLATION run={} mode={} findings={} types={}", id, run.mode(), all.size(),
                    new TreeSet<>(all.stream().map(Finding::type).toList()));
        }
        return repository.findRun(id).orElseThrow();
    }

    // mode INCREMENTAL | FULL, status OK | VIOLATIONS | FAILED, type Finding sabitleri
    private void recordRun(String mode, String status, List<Finding> findings) {
        meters.counter("routemesh.ledger.integrity.runs", "mode", mode, "status", status).increment();
        findings.forEach(finding -> meters.counter("routemesh.ledger.integrity.findings", "type", finding.type())
                .increment());
    }

    int failStaleRuns() {
        return repository.failStaleRuns(Instant.now().minus(properties.staleRunAfter()));
    }

    // --- INCREMENTAL ---

    private Chunk processChunk(UUID runId) {
        repository.setLockTimeoutMillis(properties.lockTimeout().toMillis());
        repository.lockEntriesGateExclusive();

        Checkpoint previous = repository.latestCheckpoint().orElse(null);
        long last = previous == null ? 0 : previous.lastEntryId();
        long max = repository.maxEntryId();
        if (max <= last) {
            return null;
        }
        long target = Math.min(max, last + properties.chunkEntries());
        long upper = repository.extendToWholeTransactions(last, target);

        RangeTotals totals = repository.rangeTotals(last, upper);
        ChainHasher hasher = new ChainHasher(previous == null ? ChainHasher.GENESIS : previous.chainHash());
        repository.streamEntries(last, upper,
                (id, transactionId, accountId, direction, amount) ->
                        hasher.add(id, transactionId, accountId, direction, amount));

        Findings findings = new Findings(properties.maxFindingsPerRun());
        for (Unbalanced unbalanced : repository.unbalancedTransactions(last, upper)) {
            findings.add(Finding.critical(Finding.UNBALANCED_TRANSACTION, null, null, unbalanced.transactionId(), 0L,
                    unbalanced.netDebit(), Map.of("meaning", "debit minus credit of the transaction is not zero")));
        }
        for (UUID reversal : repository.reversalMismatches(last, upper)) {
            findings.add(Finding.critical(Finding.REVERSAL_MISMATCH, null, null, reversal, null, null,
                    Map.of("meaning", "reversal does not mirror the reversed transaction")));
        }
        for (BalanceRow delta : repository.rangeBalances(last, upper)) {
            long net = repository.addToCache(delta);
            if (LIABILITY.equals(delta.accountType()) && net > 0) {
                findings.add(negativeBalance(delta.accountCode(), delta.scopeShipmentId(), net));
            }
        }
        repository.deleteZeroCacheRows();

        if (totals.debit() != totals.credit()) {
            findings.add(Finding.critical(Finding.TOTALS_MISMATCH, null, null, null, totals.debit(), totals.credit(),
                    Map.of("fromEntryId", last + 1, "toEntryId", upper)));
        }
        long count = (previous == null ? 0 : previous.entryCount()) + totals.count();
        long debit = (previous == null ? 0 : previous.totalDebitMinor()) + totals.debit();
        long credit = (previous == null ? 0 : previous.totalCreditMinor()) + totals.credit();
        repository.insertCheckpoint(runId, upper, count, debit, credit, hasher.finish());
        List<Finding> result = findings.result();
        repository.insertFindings(runId, result);
        return new Chunk(last, upper, totals.count(), result);
    }

    // --- FULL ---

    private Audit audit() {
        List<Checkpoint> checkpoints = repository.checkpointsAscending();
        Findings findings = new Findings(properties.maxFindingsPerRun());
        long previousLast = 0;
        String previousHash = ChainHasher.GENESIS;
        long previousCount = 0;
        long previousDebit = 0;
        long previousCredit = 0;
        long recomputedDebit = 0;
        long recomputedCredit = 0;
        long scanned = 0;

        for (Checkpoint checkpoint : checkpoints) {
            RangeTotals segment = repository.rangeTotals(previousLast, checkpoint.lastEntryId());
            ChainHasher hasher = new ChainHasher(previousHash);
            repository.streamEntries(previousLast, checkpoint.lastEntryId(),
                    (id, transactionId, accountId, direction, amount) ->
                            hasher.add(id, transactionId, accountId, direction, amount));
            String recomputed = hasher.finish();
            boolean intact = recomputed.equals(checkpoint.chainHash())
                    && previousCount + segment.count() == checkpoint.entryCount()
                    && previousDebit + segment.debit() == checkpoint.totalDebitMinor()
                    && previousCredit + segment.credit() == checkpoint.totalCreditMinor();
            if (!intact) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("checkpointId", checkpoint.id());
                detail.put("fromEntryId", previousLast + 1);
                detail.put("toEntryId", checkpoint.lastEntryId());
                detail.put("storedHash", checkpoint.chainHash());
                detail.put("recomputedHash", recomputed);
                detail.put("storedEntryCount", checkpoint.entryCount() - previousCount);
                detail.put("recomputedEntryCount", segment.count());
                findings.add(Finding.critical(Finding.HISTORY_TAMPERED, null, null, null,
                        checkpoint.totalDebitMinor() - previousDebit, segment.debit(), detail));
            }
            recomputedDebit += segment.debit();
            recomputedCredit += segment.credit();
            scanned += segment.count();
            previousLast = checkpoint.lastEntryId();
            previousHash = checkpoint.chainHash();
            previousCount = checkpoint.entryCount();
            previousDebit = checkpoint.totalDebitMinor();
            previousCredit = checkpoint.totalCreditMinor();
        }
        if (checkpoints.isEmpty()) {
            return new Audit(0, 0, List.of());
        }

        List<BalanceRow> recomputed = repository.rangeBalances(0, previousLast);
        Map<String, Long> cache = new HashMap<>();
        for (BalanceRow row : repository.cacheRows()) {
            cache.put(row.key(), row.netDebit());
        }
        for (BalanceRow row : recomputed) {
            Long cached = cache.remove(row.key());
            if (row.netDebit() != 0 && (cached == null || cached != row.netDebit())) {
                findings.add(cacheDrift(row, cached == null ? 0 : cached));
            }
            if (LIABILITY.equals(row.accountType()) && row.netDebit() > 0) {
                findings.add(negativeBalance(row.accountCode(), row.scopeShipmentId(), row.netDebit()));
            }
        }
        for (Map.Entry<String, Long> orphan : cache.entrySet()) {
            if (orphan.getValue() != 0) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("key", orphan.getKey());
                detail.put("meaning", "cache holds a balance the entries do not support");
                findings.add(Finding.critical(Finding.CACHE_DRIFT, null, null, null, 0L, orphan.getValue(), detail));
            }
        }
        for (Unbalanced unbalanced : repository.unbalancedTransactions(0, previousLast)) {
            findings.add(Finding.critical(Finding.UNBALANCED_TRANSACTION, null, null, unbalanced.transactionId(), 0L,
                    unbalanced.netDebit(), Map.of("meaning", "debit minus credit of the transaction is not zero")));
        }
        for (UUID reversal : repository.reversalMismatches(0, previousLast)) {
            findings.add(Finding.critical(Finding.REVERSAL_MISMATCH, null, null, reversal, null, null,
                    Map.of("meaning", "reversal does not mirror the reversed transaction")));
        }
        if (recomputedDebit != recomputedCredit) {
            findings.add(Finding.critical(Finding.TOTALS_MISMATCH, null, null, null, recomputedDebit,
                    recomputedCredit, Map.of("toEntryId", previousLast)));
        }
        return new Audit(previousLast, scanned, findings.result());
    }

    // --- yardımcılar ---

    private static Finding negativeBalance(String accountCode, UUID scope, long netDebit) {
        UUID shipment = IntegrityRepository.NO_SCOPE.equals(scope) ? null : scope;
        return Finding.critical(Finding.NEGATIVE_BALANCE, accountCode, shipment, null, 0L, netDebit,
                Map.of("meaning", "a liability account has more debits than credits"));
    }

    private static Finding cacheDrift(BalanceRow row, long cached) {
        UUID shipment = IntegrityRepository.NO_SCOPE.equals(row.scopeShipmentId()) ? null : row.scopeShipmentId();
        return Finding.critical(Finding.CACHE_DRIFT, row.accountCode(), shipment, null, row.netDebit(), cached,
                Map.of("meaning", "balance cache differs from the balance recomputed from entries"));
    }

    private void publishViolation(Run run, List<Finding> findings) {
        LedgerIntegrityViolationDetectedEvent event = new LedgerIntegrityViolationDetectedEvent(run.id(), run.mode(),
                findings.size(), (int) findings.stream().filter(f -> Finding.CRITICAL.equals(f.severity())).count(),
                new TreeSet<>(findings.stream().map(Finding::type).toList()).stream().toList());
        UUID eventId = UUID.randomUUID();
        EventEnvelope<LedgerIntegrityViolationDetectedEvent> envelope = new EventEnvelope<>(eventId,
                LedgerIntegrityViolationDetectedEvent.TYPE, LedgerIntegrityViolationDetectedEvent.VERSION,
                Instant.now(), run.id().toString(), event);
        ledgerRepository.insertOutbox(eventId, AGGREGATE_TYPE, run.id().toString(),
                LedgerIntegrityViolationDetectedEvent.TYPE, jsonMapper.writeValueAsString(envelope));
    }

    /** Koşu başına bulgu sayısını sınırlar; aşılırsa bir {@code TRUNCATED} uyarısı eklenir. */
    private static final class Findings {

        private final List<Finding> list = new ArrayList<>();
        private final int cap;
        private int dropped;

        Findings(int cap) {
            this.cap = cap;
        }

        void add(Finding finding) {
            if (list.size() < cap) {
                list.add(finding);
            } else {
                dropped++;
            }
        }

        List<Finding> result() {
            if (dropped > 0) {
                list.add(new Finding(Finding.TRUNCATED, Finding.WARNING, null, null, null, null, null,
                        Map.of("droppedFindings", dropped)));
            }
            return list;
        }
    }
}
