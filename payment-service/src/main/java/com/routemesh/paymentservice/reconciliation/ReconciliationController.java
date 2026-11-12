package com.routemesh.paymentservice.reconciliation;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.routemesh.paymentservice.reconciliation.ReconciliationRepository.Run;
import com.routemesh.paymentservice.reconciliation.ReconciliationRepository.StoredFinding;
import com.routemesh.paymentservice.reconciliation.ReconciliationService.RunAlreadyActiveException;
import com.routemesh.paymentservice.reconciliation.ReconciliationService.Trigger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** PSP mutabakatı OPS uçları. Gateway ayrıca {@code /api/v1/ops/**} için OPS rolü ister. */
@RestController
@RequestMapping("/api/v1/ops/payments")
@PreAuthorize("hasRole('OPS')")
class ReconciliationController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final Set<String> RUN_STATUSES = Set.of("RUNNING", "OK", "DISCREPANCIES", "FAILED");
    static final Set<String> FINDING_TYPES = Set.of(Finding.MISSING_AT_PSP, Finding.UNKNOWN_AT_PLATFORM,
            Finding.STATUS_MISMATCH, Finding.CAPTURED_AMOUNT_MISMATCH, Finding.REFUNDED_AMOUNT_MISMATCH,
            Finding.STUCK_TRANSACTION, Finding.PSP_REPORT_UNAVAILABLE, Finding.CLEARING_MISMATCH,
            Finding.LEDGER_UNAVAILABLE, Finding.TRUNCATED);
    static final Set<String> SEVERITIES = Set.of(Finding.CRITICAL, Finding.WARNING);

    record StartRequest(Instant windowFrom, Instant windowTo) {
    }

    record RunResponse(UUID id, String trigger, String requestedBy, String status, Instant startedAt,
                       Instant finishedAt, Instant windowFrom, Instant windowTo, long pspPaymentsSeen,
                       long intentsChecked, int findingsCount, String error) {

        static RunResponse from(Run run) {
            return new RunResponse(run.id(), run.trigger(), run.requestedBy(), run.status(), run.startedAt(),
                    run.finishedAt(), run.windowFrom(), run.windowTo(), run.pspPaymentsSeen(),
                    run.intentsChecked(), run.findingsCount(), run.error());
        }
    }

    record RunDetail(RunResponse run, Map<String, Integer> findingsByType, Map<String, Integer> findingsBySeverity) {
    }

    record RunsResponse(List<RunResponse> items, String nextCursor) {
    }

    record FindingResponse(long id, UUID runId, String type, String severity, UUID intentId, UUID shipmentId,
                           String pspPaymentId, UUID transactionId, String localStatus, String pspStatus,
                           Long localCapturedMinor, Long localRefundedMinor, Long pspCapturedMinor,
                           Long pspRefundedMinor, JsonNode detail, Instant createdAt) {
    }

    record FindingsResponse(List<FindingResponse> items, String nextCursor) {
    }

    record SummaryResponse(String status, RunResponse latestRun, UUID activeRunId, Instant lastCompletedWindowEnd,
                           Map<String, Integer> findingsByType, Map<String, Integer> findingsBySeverity) {
    }

    private final ReconciliationService service;
    private final ReconciliationRepository repository;
    private final ReconciliationProperties properties;
    private final ExecutorService executor;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    ReconciliationController(ReconciliationService service, ReconciliationRepository repository,
                             ReconciliationProperties properties, ExecutorService reconciliationExecutor,
                             JsonMapper jsonMapper, Clock clock) {
        this.service = service;
        this.repository = repository;
        this.properties = properties;
        this.executor = reconciliationExecutor;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Manuel koşu arka planda başlar ({@code 202}) ve koşu kimliğiyle izlenir; çalışan bir koşu varsa {@code 409}. */
    @PostMapping("/reconciliation-runs")
    ResponseEntity<RunResponse> start(Authentication authentication,
                                      @RequestBody(required = false) StartRequest body) {
        Instant from = body == null ? null : body.windowFrom();
        Instant to = body == null ? null : body.windowTo();
        validateWindow(from, to);
        Run run;
        try {
            run = service.start(Trigger.MANUAL, authentication.getName(), from, to);
        } catch (RunAlreadyActiveException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        Run started = run;
        executor.submit(() -> service.execute(started));
        return ResponseEntity.accepted().body(RunResponse.from(run));
    }

    @GetMapping("/reconciliation-runs")
    RunsResponse runs(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit, @RequestParam(required = false) String cursor) {
        checkLimit(limit);
        if (status != null && !RUN_STATUSES.contains(status)) {
            throw bad("unknown status: " + status);
        }
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                        .split(":", 2);
                beforeAt = Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw bad("cursor is not valid");
            }
        }
        List<Run> rows = repository.listRuns(status, beforeAt, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        List<Run> page = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            long micros = ChronoUnit.MICROS.between(Instant.EPOCH, page.getLast().startedAt());
            next = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((micros + ":" + page.getLast().id()).getBytes(StandardCharsets.UTF_8));
        }
        return new RunsResponse(page.stream().map(RunResponse::from).toList(), next);
    }

    @GetMapping("/reconciliation-runs/{id}")
    RunDetail run(@PathVariable UUID id) {
        Run run = repository.findRun(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "reconciliation run not found"));
        return new RunDetail(RunResponse.from(run), repository.findingTypeCounts(id),
                repository.findingSeverityCounts(id));
    }

    @GetMapping("/reconciliation-findings")
    FindingsResponse findings(@RequestParam(required = false) UUID runId, @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit, @RequestParam(required = false) Long cursor) {
        checkLimit(limit);
        if (type != null && !FINDING_TYPES.contains(type)) {
            throw bad("unknown type: " + type);
        }
        if (severity != null && !SEVERITIES.contains(severity)) {
            throw bad("unknown severity: " + severity);
        }
        if (cursor != null && cursor < 1) {
            throw bad("cursor must be a positive finding id");
        }
        List<StoredFinding> rows = repository.listFindings(runId, type, severity, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<StoredFinding> page = more ? rows.subList(0, limit) : rows;
        return new FindingsResponse(page.stream().map(this::finding).toList(),
                more ? String.valueOf(page.getLast().id()) : null);
    }

    /**
     * Özet: son biten koşuya göre {@code OK}, {@code DISCREPANCIES}, {@code DEGRADED} (son koşu {@code FAILED},
     * örneğin PSP'ye ulaşılamadı) ya da hiç koşu yoksa {@code UNKNOWN}; ve o koşunun bulgu sayıları.
     */
    @GetMapping("/reconciliation")
    SummaryResponse summary() {
        Run latest = repository.latestFinishedRun().orElse(null);
        String status = latest == null ? "UNKNOWN" : switch (latest.status()) {
            case "OK" -> "OK";
            case "DISCREPANCIES" -> "DISCREPANCIES";
            default -> "DEGRADED";
        };
        return new SummaryResponse(status, latest == null ? null : RunResponse.from(latest),
                repository.activeRun().map(Run::id).orElse(null), repository.lastCompletedWindowEnd().orElse(null),
                latest == null ? Map.of() : repository.findingTypeCounts(latest.id()),
                latest == null ? Map.of() : repository.findingSeverityCounts(latest.id()));
    }

    private void validateWindow(Instant from, Instant to) {
        Instant now = clock.instant();
        if (to != null && to.isAfter(now.plus(Duration.ofMinutes(1)))) {
            throw bad("windowTo must not be in the future");
        }
        if (from != null && !from.isBefore(to != null ? to : now)) {
            throw bad("windowFrom must be before windowTo");
        }
        Instant effectiveTo = to != null ? to : now;
        if (from != null && Duration.between(from, effectiveTo).compareTo(properties.maxManualWindow()) > 0) {
            throw bad("the window must not be longer than " + properties.maxManualWindow());
        }
    }

    private FindingResponse finding(StoredFinding f) {
        return new FindingResponse(f.id(), f.runId(), f.type(), f.severity(), f.intentId(), f.shipmentId(),
                f.pspPaymentId(), f.transactionId(), f.localStatus(), f.pspStatus(), f.localCapturedMinor(),
                f.localRefundedMinor(), f.pspCapturedMinor(), f.pspRefundedMinor(), jsonMapper.readTree(f.detail()),
                f.createdAt());
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw bad("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
