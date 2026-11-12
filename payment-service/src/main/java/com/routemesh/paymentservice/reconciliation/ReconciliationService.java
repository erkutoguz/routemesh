package com.routemesh.paymentservice.reconciliation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.contracts.events.EventEnvelope;
import com.routemesh.contracts.events.ReconciliationDiscrepancyDetectedEvent;
import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.psp.PspClient;
import com.routemesh.paymentservice.psp.PspClient.PspPayment;
import com.routemesh.paymentservice.psp.PspClient.PspReportPage;
import com.routemesh.paymentservice.psp.PspUnavailableException;
import com.routemesh.paymentservice.reconciliation.ReconciliationRepository.Run;
import com.routemesh.paymentservice.reconciliation.ReconciliationRepository.StuckTransaction;
import com.routemesh.paymentservice.repository.PaymentRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * PSP mutabakatı: PSP'nin rapor ettiği ödemeleri platformdaki ödeme niyetleriyle {@code psp_payment_id} üzerinden
 * karşılaştırır. Yalnızca raporlar; hiçbir ödemeyi ya da niyeti değiştirmez, karar OPS'a aittir.
 */
@Service
public class ReconciliationService {

    public enum Trigger { SCHEDULED, MANUAL }

    public static class RunAlreadyActiveException extends RuntimeException {
        public RunAlreadyActiveException() {
            super("a reconciliation run is already in progress");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int ID_BATCH = 500;
    private static final int PROBES = 3;
    private static final String AGGREGATE_TYPE = "payment";

    private final ReconciliationRepository repository;
    private final PaymentRepository payments;
    private final PspClient psp;
    private final ReconciliationProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final JsonMapper jsonMapper;
    private final ClearingCrossCheck clearing;
    private final MeterRegistry meters;

    public ReconciliationService(ReconciliationRepository repository, PaymentRepository payments, PspClient psp,
                                 ReconciliationProperties properties, Clock clock,
                                 PlatformTransactionManager transactionManager, JsonMapper jsonMapper,
                                 ClearingCrossCheck clearing, MeterRegistry meters) {
        this.meters = meters;
        // Her scrape'te küçük bir sorgu; yeniden başlatma ve çok kopya durumunda da doğru kalır
        Gauge.builder("routemesh.payment.reconciliation.last.success.timestamp.seconds", repository,
                r -> {
                    try {
                        return r.lastSuccessfulFinishedAt().map(at -> at.toEpochMilli() / 1000.0).orElse(0.0);
                    } catch (RuntimeException e) {
                        return Double.NaN;
                    }
                }).register(meters);
        this.repository = repository;
        this.payments = payments;
        this.psp = psp;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.jsonMapper = jsonMapper;
        this.clearing = clearing;
    }

    /**
     * Koşu kaydını açar. Pencere verilmezse son tamamlanan koşunun sonundan {@code overlap} kadar geriden
     * şimdiye kadardır (hiç koşu yoksa {@code initialLookback}).
     */
    public Run start(Trigger trigger, String requestedBy, Instant from, Instant to) {
        Instant windowTo = to != null ? to : clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant windowFrom = from != null ? from : repository.lastCompletedWindowEnd()
                .map(end -> end.minus(properties.overlap()))
                .orElse(windowTo.minus(properties.initialLookback()));
        if (!windowFrom.isBefore(windowTo)) {
            windowFrom = windowTo.minus(properties.overlap());
        }
        try {
            return repository.insertRun(trigger.name(), requestedBy, windowFrom, windowTo);
        } catch (DuplicateKeyException e) {
            throw new RunAlreadyActiveException();
        }
    }

    public Run run(Trigger trigger, String requestedBy, Instant from, Instant to) {
        return execute(start(trigger, requestedBy, from, to));
    }

    /** Başlatılmış koşuyu yürütür ve son halini döner. Hata koşuyu {@code FAILED} yapar, fırlatmaz. */
    public Run execute(Run run) {
        Findings findings = new Findings(properties.maxFindingsPerRun());
        Counts counts = new Counts();
        String error = null;
        boolean failed = false;
        try {
            List<PaymentIntent> window = loadWindow(run);
            stuckTransactions(findings);
            clearing.check(window, clock.instant()).forEach(findings::add);
            reconcile(run, window, findings, counts);
        } catch (PspUnavailableException e) {
            failed = true;
            error = e.getMessage();
            findings.add(reportUnavailable("UNREACHABLE", Map.of("error", String.valueOf(e.getMessage()))));
        } catch (RuntimeException e) {
            failed = true;
            error = String.valueOf(e.getMessage());
            log.error("RECONCILIATION_RUN_FAILED run={}", run.id(), e);
        }
        List<Finding> result = findings.result();
        String status = failed ? "FAILED" : result.isEmpty() ? "OK" : "DISCREPANCIES";
        String storedError = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
        tx.executeWithoutResult(s -> {
            repository.insertFindings(run.id(), result);
            repository.finishRun(run.id(), status, counts.pspSeen, counts.intentsChecked, result.size(),
                    storedError);
            if (!result.isEmpty()) {
                publishDiscrepancy(run, status, result);
            }
        });
        // status OK | DISCREPANCIES | FAILED; bulgu türleri Finding sabitleridir
        meters.counter("routemesh.payment.reconciliation.runs", "status", status).increment();
        result.forEach(finding -> meters.counter("routemesh.payment.reconciliation.findings", "type", finding.type())
                .increment());
        if (!result.isEmpty()) {
            log.error("RECONCILIATION_DISCREPANCY run={} findings={} types={}", run.id(), result.size(),
                    new TreeSet<>(result.stream().map(Finding::type).toList()));
        }
        return repository.findRun(run.id()).orElseThrow();
    }

    private void publishDiscrepancy(Run run, String status, List<Finding> findings) {
        ReconciliationDiscrepancyDetectedEvent event = new ReconciliationDiscrepancyDetectedEvent(run.id(), status,
                findings.size(), (int) findings.stream().filter(f -> Finding.CRITICAL.equals(f.severity())).count(),
                new TreeSet<>(findings.stream().map(Finding::type).toList()).stream().toList(), run.windowFrom(),
                run.windowTo());
        UUID eventId = UUID.randomUUID();
        EventEnvelope<ReconciliationDiscrepancyDetectedEvent> envelope = new EventEnvelope<>(eventId,
                ReconciliationDiscrepancyDetectedEvent.TYPE, ReconciliationDiscrepancyDetectedEvent.VERSION,
                clock.instant(), run.id().toString(), event);
        payments.insertOutbox(eventId, AGGREGATE_TYPE, run.id().toString(),
                ReconciliationDiscrepancyDetectedEvent.TYPE, jsonMapper.writeValueAsString(envelope));
    }

    int failStaleRuns() {
        return repository.failStaleRuns(clock.instant().minus(properties.staleRunAfter()));
    }

    /** Pencerede güncellenen tüm ödeme niyetleri ({@code updated_at, id} anahtar kümeli sayfalarla). */
    private List<PaymentIntent> loadWindow(Run run) {
        List<PaymentIntent> window = new ArrayList<>();
        Instant afterAt = null;
        UUID afterId = null;
        List<PaymentIntent> batch;
        do {
            batch = payments.findIntentsUpdatedBetween(run.windowFrom(), run.windowTo(), afterAt, afterId,
                    properties.pageSize());
            window.addAll(batch);
            if (!batch.isEmpty()) {
                PaymentIntent last = batch.getLast();
                afterAt = last.updatedAt();
                afterId = last.id();
            }
        } while (batch.size() == properties.pageSize());
        return window;
    }

    // --- PSP karşılaştırması ---

    private void reconcile(Run run, List<PaymentIntent> window, Findings findings, Counts counts) {
        Instant now = clock.instant();
        Map<String, PspPayment> pspPayments = new LinkedHashMap<>();
        String cursor = null;
        do {
            PspReportPage page = psp.listPayments(run.windowFrom(), run.windowTo(), properties.pageSize(), cursor);
            page.items().forEach(payment -> pspPayments.put(payment.id(), payment));
            cursor = page.nextCursor();
        } while (cursor != null);
        counts.pspSeen = pspPayments.size();

        Map<String, PaymentIntent> byPspId = new LinkedHashMap<>();
        window.forEach(intent -> byPspId.put(intent.pspPaymentId(), intent));

        Set<String> unmatched = new LinkedHashSet<>(pspPayments.keySet());
        unmatched.removeAll(byPspId.keySet());
        List<String> ids = new ArrayList<>(unmatched);
        for (int i = 0; i < ids.size(); i += ID_BATCH) {
            payments.findIntentsByPspIds(ids.subList(i, Math.min(ids.size(), i + ID_BATCH)))
                    .forEach(intent -> byPspId.put(intent.pspPaymentId(), intent));
        }
        counts.intentsChecked = byPspId.size();

        for (PspPayment payment : pspPayments.values()) {
            PaymentIntent intent = byPspId.get(payment.id());
            if (intent == null) {
                ReconciliationRules.unknownAtPlatform(payment, now, properties.grace()).ifPresent(findings::add);
            } else {
                ReconciliationRules.compare(intent, payment, now, properties.grace()).forEach(findings::add);
            }
        }

        List<PaymentIntent> candidates = window.stream()
                .filter(intent -> !pspPayments.containsKey(intent.pspPaymentId()))
                .filter(intent -> !intent.updatedAt().isAfter(now.minus(properties.grace())))
                .toList();
        confirmMissing(candidates, pspPayments.isEmpty(), now, findings);
    }

    /**
     * Raporda görünmeyen niyetleri PSP'ye tek tek sorar: raporda olmaması PSP'nin kaydı olmadığı anlamına gelmez
     * (PSP tarafında yakın zamanda güncellenmemiş olabilir). Rapor boşken örneklenen niyetlerin hepsi PSP'de de yoksa
     * (PSP verisini kaybetmiş, örn. bellek tabanlı simülatör yeniden başladı) yüzlerce bulgu yerine tek koruma bulgusu
     * yazılır.
     */
    private void confirmMissing(List<PaymentIntent> candidates, boolean reportEmpty, Instant now, Findings findings) {
        if (candidates.isEmpty()) {
            return;
        }
        if (reportEmpty) {
            List<PaymentIntent> sample = candidates.subList(0, Math.min(PROBES, candidates.size()));
            boolean allAbsent = sample.stream().allMatch(intent -> psp.findPayment(intent.pspPaymentId()).isEmpty());
            if (allAbsent) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("intentsInWindow", candidates.size());
                detail.put("probed", sample.stream().map(PaymentIntent::pspPaymentId).toList());
                findings.add(reportUnavailable("EMPTY_REPORT", detail));
                return;
            }
        }
        List<Finding> missing = new ArrayList<>();
        int lookups = 0;
        int skipped = 0;
        for (PaymentIntent intent : candidates) {
            if (lookups >= properties.maxLookups()) {
                skipped++;
                continue;
            }
            lookups++;
            Optional<PspPayment> found = psp.findPayment(intent.pspPaymentId());
            if (found.isPresent()) {
                ReconciliationRules.compare(intent, found.get(), now, properties.grace()).forEach(findings::add);
            } else {
                missing.add(ReconciliationRules.missingAtPsp(intent));
            }
        }
        if (missing.size() > properties.missingFloodThreshold()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("missingCount", missing.size());
            detail.put("sample", missing.stream().limit(5).map(Finding::pspPaymentId).toList());
            findings.add(reportUnavailable("MASS_MISSING", detail));
        } else {
            missing.forEach(findings::add);
        }
        if (skipped > 0) {
            findings.add(new Finding(Finding.TRUNCATED, Finding.WARNING, null, null, null, null, null, null, null,
                    null, null, null, Map.of("skippedLookups", skipped, "meaning",
                    "too many unreported payments to look up individually in one run")));
        }
    }

    private void stuckTransactions(Findings findings) {
        Instant cutoff = clock.instant().minus(properties.stuckAfter());
        for (StuckTransaction stuck : repository.findStuckTransactions(cutoff, properties.maxFindingsPerRun())) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", stuck.type());
            detail.put("status", stuck.status());
            detail.put("amountMinor", stuck.amountMinor());
            detail.put("attempts", stuck.attempts());
            detail.put("lastError", stuck.lastError());
            detail.put("openSince", stuck.updatedAt());
            findings.add(new Finding(Finding.STUCK_TRANSACTION, Finding.WARNING, stuck.intentId(),
                    stuck.shipmentId(), stuck.pspPaymentId(), stuck.id(), null, null, null, null, null, null, detail));
        }
    }

    private static Finding reportUnavailable(String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.putAll(extra);
        return new Finding(Finding.PSP_REPORT_UNAVAILABLE, Finding.CRITICAL, null, null, null, null, null, null,
                null, null, null, null, detail);
    }

    private static final class Counts {
        private long pspSeen;
        private long intentsChecked;
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
                list.add(new Finding(Finding.TRUNCATED, Finding.WARNING, null, null, null, null, null, null, null,
                        null, null, null, Map.of("droppedFindings", dropped)));
            }
            return list;
        }
    }
}
