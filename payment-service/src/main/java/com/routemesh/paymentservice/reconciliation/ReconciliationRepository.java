package com.routemesh.paymentservice.reconciliation;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class ReconciliationRepository {

    private static final String RUN_COLUMNS = "id, trigger_source, requested_by, status, started_at, finished_at, "
            + "window_from, window_to, psp_payments_seen, intents_checked, findings_count, error";

    public record Run(UUID id, String trigger, String requestedBy, String status, Instant startedAt,
                      Instant finishedAt, Instant windowFrom, Instant windowTo, long pspPaymentsSeen,
                      long intentsChecked, int findingsCount, String error) {
    }

    public record StoredFinding(long id, UUID runId, String type, String severity, UUID intentId, UUID shipmentId,
                                String pspPaymentId, UUID transactionId, String localStatus, String pspStatus,
                                Long localCapturedMinor, Long localRefundedMinor, Long pspCapturedMinor,
                                Long pspRefundedMinor, String detail, Instant createdAt) {
    }

    public record StuckTransaction(UUID id, UUID intentId, UUID shipmentId, String pspPaymentId, String type,
                                   String status, long amountMinor, int attempts, String lastError,
                                   Instant updatedAt) {
    }

    private static final RowMapper<Run> RUN = (rs, n) -> new Run(rs.getObject("id", UUID.class),
            rs.getString("trigger_source"), rs.getString("requested_by"), rs.getString("status"),
            instant(rs.getObject("started_at", OffsetDateTime.class)),
            instant(rs.getObject("finished_at", OffsetDateTime.class)),
            instant(rs.getObject("window_from", OffsetDateTime.class)),
            instant(rs.getObject("window_to", OffsetDateTime.class)), rs.getLong("psp_payments_seen"),
            rs.getLong("intents_checked"), rs.getInt("findings_count"), rs.getString("error"));

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public ReconciliationRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /** Koşu kaydını açar; zaten çalışan bir koşu varsa benzersiz indeks {@code DuplicateKeyException} fırlatır. */
    public Run insertRun(String trigger, String requestedBy, Instant windowFrom, Instant windowTo) {
        return jdbc.sql("INSERT INTO reconciliation_runs (trigger_source, requested_by, window_from, window_to) "
                        + "VALUES (:trigger, :by, :from, :to) RETURNING " + RUN_COLUMNS)
                .param("trigger", trigger).param("by", requestedBy)
                .param("from", OffsetDateTime.ofInstant(windowFrom, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(windowTo, ZoneOffset.UTC))
                .query(RUN).single();
    }

    public void finishRun(UUID id, String status, long pspPaymentsSeen, long intentsChecked, int findings,
                          String error) {
        jdbc.sql("UPDATE reconciliation_runs SET status = :status, finished_at = now(), "
                        + "psp_payments_seen = :seen, intents_checked = :checked, findings_count = :findings, "
                        + "error = :error WHERE id = :id")
                .param("status", status).param("seen", pspPaymentsSeen).param("checked", intentsChecked)
                .param("findings", findings).param("error", error).param("id", id)
                .update();
    }

    public int failStaleRuns(Instant cutoff) {
        return jdbc.sql("UPDATE reconciliation_runs SET status = 'FAILED', finished_at = now(), "
                        + "error = 'run did not finish in time' WHERE status = 'RUNNING' AND started_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    public Optional<Run> findRun(UUID id) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM reconciliation_runs WHERE id = :id").param("id", id)
                .query(RUN).optional();
    }

    public Optional<Run> latestFinishedRun() {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM reconciliation_runs WHERE status <> 'RUNNING' "
                        + "ORDER BY started_at DESC, id DESC LIMIT 1")
                .query(RUN).optional();
    }

    public Optional<Run> activeRun() {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM reconciliation_runs WHERE status = 'RUNNING'")
                .query(RUN).optional();
    }

    public List<Run> listRuns(String status, Instant beforeStartedAt, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + RUN_COLUMNS + " FROM reconciliation_runs WHERE true");
        if (status != null) {
            sql.append(" AND status = :status");
        }
        if (beforeStartedAt != null) {
            sql.append(" AND (started_at, id) < (:at, :beforeId)");
        }
        sql.append(" ORDER BY started_at DESC, id DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (status != null) {
            spec = spec.param("status", status);
        }
        if (beforeStartedAt != null) {
            spec = spec.param("at", OffsetDateTime.ofInstant(beforeStartedAt, ZoneOffset.UTC))
                    .param("beforeId", beforeId);
        }
        return spec.query(RUN).list();
    }

    public List<StoredFinding> listFindings(UUID runId, String type, String severity, Long beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, run_id, type, severity, intent_id, shipment_id, "
                + "psp_payment_id, transaction_id, local_status, psp_status, local_captured_minor, "
                + "local_refunded_minor, psp_captured_minor, psp_refunded_minor, detail::text AS detail, created_at "
                + "FROM reconciliation_findings WHERE true");
        if (runId != null) {
            sql.append(" AND run_id = :run");
        }
        if (type != null) {
            sql.append(" AND type = :type");
        }
        if (severity != null) {
            sql.append(" AND severity = :severity");
        }
        if (beforeId != null) {
            sql.append(" AND id < :before");
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (runId != null) {
            spec = spec.param("run", runId);
        }
        if (type != null) {
            spec = spec.param("type", type);
        }
        if (severity != null) {
            spec = spec.param("severity", severity);
        }
        if (beforeId != null) {
            spec = spec.param("before", beforeId);
        }
        return spec.query((rs, n) -> new StoredFinding(rs.getLong("id"), rs.getObject("run_id", UUID.class),
                rs.getString("type"), rs.getString("severity"), rs.getObject("intent_id", UUID.class),
                rs.getObject("shipment_id", UUID.class), rs.getString("psp_payment_id"),
                rs.getObject("transaction_id", UUID.class), rs.getString("local_status"),
                rs.getString("psp_status"), (Long) rs.getObject("local_captured_minor"),
                (Long) rs.getObject("local_refunded_minor"), (Long) rs.getObject("psp_captured_minor"),
                (Long) rs.getObject("psp_refunded_minor"), rs.getString("detail"),
                instant(rs.getObject("created_at", OffsetDateTime.class)))).list();
    }

    public Map<String, Integer> findingTypeCounts(UUID runId) {
        return counts("type", runId);
    }

    public Map<String, Integer> findingSeverityCounts(UUID runId) {
        return counts("severity", runId);
    }

    private Map<String, Integer> counts(String column, UUID runId) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        jdbc.sql("SELECT " + column + " AS k, count(*) AS n FROM reconciliation_findings WHERE run_id = :run "
                        + "GROUP BY " + column)
                .param("run", runId)
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        counts.put(rs.getString("k"), rs.getInt("n")));
        return counts;
    }

    /** Son başarılı (bulgulu ya da bulgusuz) koşunun bitiş zamanı. */
    public Optional<Instant> lastSuccessfulFinishedAt() {
        return jdbc.sql("SELECT max(finished_at) FROM reconciliation_runs WHERE status IN ('OK', 'DISCREPANCIES')")
                .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant);
    }

    /** Son başarılı (bulgulu ya da bulgusuz) koşunun pencere sonu. */
    public Optional<Instant> lastCompletedWindowEnd() {
        return jdbc.sql("SELECT max(window_to) FROM reconciliation_runs WHERE status IN ('OK', 'DISCREPANCIES')")
                .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant);
    }

    public void insertFindings(UUID runId, List<Finding> findings) {
        for (Finding f : findings) {
            jdbc.sql("INSERT INTO reconciliation_findings (run_id, type, severity, intent_id, shipment_id, "
                            + "psp_payment_id, transaction_id, local_status, psp_status, local_captured_minor, "
                            + "local_refunded_minor, psp_captured_minor, psp_refunded_minor, detail) VALUES (:run, "
                            + ":type, :severity, :intent, :shipment, :psp, :tx, :localStatus, :pspStatus, "
                            + ":localCaptured, :localRefunded, :pspCaptured, :pspRefunded, CAST(:detail AS jsonb))")
                    .param("run", runId).param("type", f.type()).param("severity", f.severity())
                    .param("intent", f.intentId(), java.sql.Types.OTHER)
                    .param("shipment", f.shipmentId(), java.sql.Types.OTHER)
                    .param("psp", f.pspPaymentId(), java.sql.Types.VARCHAR)
                    .param("tx", f.transactionId(), java.sql.Types.OTHER)
                    .param("localStatus", f.localStatus(), java.sql.Types.VARCHAR)
                    .param("pspStatus", f.pspStatus(), java.sql.Types.VARCHAR)
                    .param("localCaptured", f.localCapturedMinor(), java.sql.Types.BIGINT)
                    .param("localRefunded", f.localRefundedMinor(), java.sql.Types.BIGINT)
                    .param("pspCaptured", f.pspCapturedMinor(), java.sql.Types.BIGINT)
                    .param("pspRefunded", f.pspRefundedMinor(), java.sql.Types.BIGINT)
                    .param("detail", jsonMapper.writeValueAsString(f.detail() == null ? Map.of() : f.detail()))
                    .update();
        }
    }

    /** Eşikten uzun süredir açık kalmış PSP işlemleri (ağ kopması, takılı yeniden denemeler). */
    public List<StuckTransaction> findStuckTransactions(Instant cutoff, int limit) {
        return jdbc.sql("SELECT t.id, t.intent_id, i.shipment_id, i.psp_payment_id, t.type, t.status, "
                        + "t.amount_minor, t.attempts, t.last_error, t.updated_at FROM payment_transactions t "
                        + "JOIN payment_intents i ON i.id = t.intent_id "
                        + "WHERE t.status IN ('PENDING', 'IN_PROGRESS', 'UNKNOWN') AND t.updated_at < :cutoff "
                        + "ORDER BY t.updated_at, t.id LIMIT :limit")
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC)).param("limit", limit)
                .query((rs, n) -> new StuckTransaction(rs.getObject("id", UUID.class),
                        rs.getObject("intent_id", UUID.class), rs.getObject("shipment_id", UUID.class),
                        rs.getString("psp_payment_id"), rs.getString("type"), rs.getString("status"),
                        rs.getLong("amount_minor"), rs.getInt("attempts"), rs.getString("last_error"),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
