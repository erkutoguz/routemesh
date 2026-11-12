package com.routemesh.ledgerservice.integrity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

import com.routemesh.ledgerservice.integrity.IntegrityRepository.BalanceRow;
import com.routemesh.ledgerservice.integrity.IntegrityRepository.Checkpoint;
import com.routemesh.ledgerservice.integrity.IntegrityRepository.Run;
import com.routemesh.ledgerservice.integrity.IntegrityRepository.StoredFinding;
import com.routemesh.ledgerservice.integrity.IntegrityService.Mode;
import com.routemesh.ledgerservice.integrity.IntegrityService.RunAlreadyActiveException;
import com.routemesh.ledgerservice.integrity.IntegrityService.Trigger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Defter bütünlük denetimi OPS uçları. Gateway ayrıca {@code /api/v1/ops/**} için OPS rolü ister. */
@RestController
@RequestMapping("/api/v1/ops/ledger")
@PreAuthorize("hasRole('OPS')")
class IntegrityController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    record StartRequest(String mode) {
    }

    record RunResponse(UUID id, String mode, String trigger, String requestedBy, String status, Instant startedAt,
                       Instant finishedAt, Long fromEntryId, Long toEntryId, long entriesScanned, int findingsCount,
                       String error) {

        static RunResponse from(Run run) {
            return new RunResponse(run.id(), run.mode(), run.trigger(), run.requestedBy(), run.status(),
                    run.startedAt(), run.finishedAt(), run.fromEntryId(), run.toEntryId(), run.entriesScanned(),
                    run.findingsCount(), run.error());
        }
    }

    record RunDetail(RunResponse run, Map<String, Integer> findingsByType) {
    }

    record RunsResponse(List<RunResponse> items, String nextCursor) {
    }

    record FindingResponse(long id, UUID runId, String type, String severity, String accountCode, UUID shipmentId,
                           UUID transactionId, Long expectedMinor, Long actualMinor, JsonNode detail,
                           Instant createdAt) {
    }

    record FindingsResponse(List<FindingResponse> items, String nextCursor) {
    }

    record CheckpointResponse(long id, UUID runId, Instant takenAt, long lastEntryId, long entryCount,
                              long totalDebitMinor, long totalCreditMinor, String chainHash) {

        static CheckpointResponse from(Checkpoint c) {
            return new CheckpointResponse(c.id(), c.runId(), c.takenAt(), c.lastEntryId(), c.entryCount(),
                    c.totalDebitMinor(), c.totalCreditMinor(), c.chainHash());
        }
    }

    record CheckpointsResponse(List<CheckpointResponse> items, String nextCursor) {
    }

    record NegativeBalance(String accountCode, UUID shipmentId, long netDebitMinor) {
    }

    record SummaryResponse(String status, RunResponse latestIncremental, RunResponse latestFull,
                           CheckpointResponse latestCheckpoint, List<NegativeBalance> negativeBalances) {
    }

    private final IntegrityService service;
    private final IntegrityRepository repository;
    private final ExecutorService executor;
    private final JsonMapper jsonMapper;

    IntegrityController(IntegrityService service, IntegrityRepository repository, ExecutorService integrityExecutor,
                        JsonMapper jsonMapper) {
        this.service = service;
        this.repository = repository;
        this.executor = integrityExecutor;
        this.jsonMapper = jsonMapper;
    }

    /** INCREMENTAL eşzamanlı çalışır ({@code 200}); FULL arka planda başlar ({@code 202}), koşu kimliğiyle izlenir. */
    @PostMapping("/integrity-runs")
    ResponseEntity<RunResponse> start(Authentication authentication, @RequestBody(required = false) StartRequest body) {
        Mode mode = parseMode(body == null ? null : body.mode());
        String requestedBy = authentication.getName();
        Run run;
        try {
            run = service.start(mode, Trigger.MANUAL, requestedBy);
        } catch (RunAlreadyActiveException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        if (mode == Mode.FULL) {
            Run started = run;
            executor.submit(() -> service.execute(started));
            return ResponseEntity.accepted().body(RunResponse.from(run));
        }
        return ResponseEntity.ok(RunResponse.from(service.execute(run)));
    }

    @GetMapping("/integrity-runs")
    RunsResponse runs(@RequestParam(required = false) String mode, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit, @RequestParam(required = false) String cursor) {
        checkLimit(limit);
        if (mode != null) {
            parseMode(mode);
        }
        if (status != null && !List.of("RUNNING", "OK", "VIOLATIONS", "FAILED").contains(status)) {
            throw bad("unknown status: " + status);
        }
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null) {
            String[] parts = decode(cursor).split(":", 2);
            try {
                beforeAt = Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw bad("cursor is not valid");
            }
        }
        List<Run> rows = repository.listRuns(mode, status, beforeAt, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        List<Run> page = more ? rows.subList(0, limit) : rows;
        String next = more ? encode(ChronoUnit.MICROS.between(Instant.EPOCH, page.getLast().startedAt()) + ":"
                + page.getLast().id()) : null;
        return new RunsResponse(page.stream().map(RunResponse::from).toList(), next);
    }

    @GetMapping("/integrity-runs/{id}")
    RunDetail run(@PathVariable UUID id) {
        Run run = repository.findRun(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "integrity run not found"));
        return new RunDetail(RunResponse.from(run), repository.findingTypeCounts(id));
    }

    @GetMapping("/findings")
    FindingsResponse findings(@RequestParam(required = false) UUID runId, @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit, @RequestParam(required = false) Long cursor) {
        checkLimit(limit);
        if (cursor != null && cursor < 1) {
            throw bad("cursor must be a positive finding id");
        }
        List<StoredFinding> rows = repository.listFindings(runId, type, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<StoredFinding> page = more ? rows.subList(0, limit) : rows;
        return new FindingsResponse(page.stream().map(this::finding).toList(),
                more ? String.valueOf(page.getLast().id()) : null);
    }

    @GetMapping("/checkpoints")
    CheckpointsResponse checkpoints(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) Long cursor) {
        checkLimit(limit);
        if (cursor != null && cursor < 1) {
            throw bad("cursor must be a positive checkpoint id");
        }
        List<Checkpoint> rows = repository.listCheckpoints(cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<Checkpoint> page = more ? rows.subList(0, limit) : rows;
        return new CheckpointsResponse(page.stream().map(CheckpointResponse::from).toList(),
                more ? String.valueOf(page.getLast().id()) : null);
    }

    /** Özet: son koşular, son checkpoint ve önbellekteki eksi bakiyeler. */
    @GetMapping("/integrity")
    SummaryResponse summary() {
        Run incremental = repository.latestRun("INCREMENTAL").orElse(null);
        Run full = repository.latestRun("FULL").orElse(null);
        List<BalanceRow> negatives = repository.negativeLiabilities(20);
        String status;
        if (incremental == null && full == null) {
            status = "UNKNOWN";
        } else if (!negatives.isEmpty() || hasStatus(incremental, "VIOLATIONS") || hasStatus(full, "VIOLATIONS")) {
            status = "VIOLATIONS";
        } else if (hasStatus(incremental, "FAILED") || hasStatus(full, "FAILED")) {
            status = "DEGRADED";
        } else {
            status = "HEALTHY";
        }
        return new SummaryResponse(status, incremental == null ? null : RunResponse.from(incremental),
                full == null ? null : RunResponse.from(full),
                repository.latestCheckpoint().map(CheckpointResponse::from).orElse(null),
                negatives.stream().map(b -> new NegativeBalance(b.accountCode(),
                        IntegrityRepository.NO_SCOPE.equals(b.scopeShipmentId()) ? null : b.scopeShipmentId(),
                        b.netDebit())).toList());
    }

    private FindingResponse finding(StoredFinding f) {
        return new FindingResponse(f.id(), f.runId(), f.type(), f.severity(), f.accountCode(), f.shipmentId(),
                f.transactionId(), f.expectedMinor(), f.actualMinor(), jsonMapper.readTree(f.detail()),
                f.createdAt());
    }

    private static boolean hasStatus(Run run, String status) {
        return run != null && status.equals(run.status());
    }

    private static Mode parseMode(String value) {
        if (value == null) {
            return Mode.INCREMENTAL;
        }
        try {
            return Mode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw bad("mode must be INCREMENTAL or FULL");
        }
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw bad("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw bad("cursor is not valid");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
