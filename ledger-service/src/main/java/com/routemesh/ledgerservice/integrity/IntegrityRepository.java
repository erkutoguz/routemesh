package com.routemesh.ledgerservice.integrity;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.routemesh.ledgerservice.repository.LedgerRepository;

import tools.jackson.databind.json.JsonMapper;

@Repository
class IntegrityRepository {

    static final UUID NO_SCOPE = new UUID(0, 0);

    private static final String SIGNED = "CASE e.direction WHEN 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END";
    private static final String RUN_COLUMNS = "id, mode, trigger_source, requested_by, status, started_at, "
            + "finished_at, from_entry_id, to_entry_id, entries_scanned, findings_count, error";

    record Run(UUID id, String mode, String trigger, String requestedBy, String status, Instant startedAt,
               Instant finishedAt, Long fromEntryId, Long toEntryId, long entriesScanned, int findingsCount,
               String error) {
    }

    record Checkpoint(long id, UUID runId, Instant takenAt, long lastEntryId, long entryCount, long totalDebitMinor,
                      long totalCreditMinor, String chainHash) {
    }

    record BalanceRow(UUID accountId, String accountCode, String accountType, UUID scopeShipmentId, long netDebit) {

        String key() {
            return accountId + "/" + scopeShipmentId;
        }
    }

    record RangeTotals(long count, long debit, long credit) {
    }

    record StoredFinding(long id, UUID runId, String type, String severity, String accountCode, UUID shipmentId,
                         UUID transactionId, Long expectedMinor, Long actualMinor, String detail, Instant createdAt) {
    }

    private static final RowMapper<Run> RUN = (rs, n) -> new Run(rs.getObject("id", UUID.class),
            rs.getString("mode"), rs.getString("trigger_source"), rs.getString("requested_by"),
            rs.getString("status"), instant(rs.getObject("started_at", OffsetDateTime.class)),
            instant(rs.getObject("finished_at", OffsetDateTime.class)), (Long) rs.getObject("from_entry_id"),
            (Long) rs.getObject("to_entry_id"), rs.getLong("entries_scanned"), rs.getInt("findings_count"),
            rs.getString("error"));

    private static final RowMapper<Checkpoint> CHECKPOINT = (rs, n) -> new Checkpoint(rs.getLong("id"),
            rs.getObject("run_id", UUID.class), instant(rs.getObject("taken_at", OffsetDateTime.class)),
            rs.getLong("last_entry_id"), rs.getLong("entry_count"), rs.getLong("total_debit_minor"),
            rs.getLong("total_credit_minor"), rs.getString("chain_hash"));

    private static final RowMapper<BalanceRow> BALANCE = (rs, n) -> new BalanceRow(
            rs.getObject("account_id", UUID.class), rs.getString("code"), rs.getString("type"),
            rs.getObject("scope", UUID.class), rs.getLong("net"));

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    IntegrityRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    // --- koşular ---

    Run insertRun(String mode, String trigger, String requestedBy) {
        return jdbc.sql("INSERT INTO integrity_runs (mode, trigger_source, requested_by) VALUES (:mode, :trigger, "
                        + ":by) RETURNING " + RUN_COLUMNS)
                .param("mode", mode).param("trigger", trigger).param("by", requestedBy)
                .query(RUN).single();
    }

    void finishRun(UUID id, String status, Long from, Long to, long scanned, int findings, String error) {
        jdbc.sql("UPDATE integrity_runs SET status = :status, finished_at = now(), from_entry_id = :from, "
                        + "to_entry_id = :to, entries_scanned = :scanned, findings_count = :findings, error = :error "
                        + "WHERE id = :id")
                .param("status", status).param("from", from, java.sql.Types.BIGINT)
                .param("to", to, java.sql.Types.BIGINT).param("scanned", scanned).param("findings", findings)
                .param("error", error).param("id", id)
                .update();
    }

    int failStaleRuns(Instant cutoff) {
        return jdbc.sql("UPDATE integrity_runs SET status = 'FAILED', finished_at = now(), "
                        + "error = 'run did not finish in time' WHERE status = 'RUNNING' AND started_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    Optional<Run> findRun(UUID id) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM integrity_runs WHERE id = :id").param("id", id)
                .query(RUN).optional();
    }

    Optional<Run> latestRun(String mode) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM integrity_runs WHERE mode = :mode AND status <> 'RUNNING' "
                        + "ORDER BY started_at DESC, id DESC LIMIT 1")
                .param("mode", mode).query(RUN).optional();
    }

    List<Run> listRuns(String mode, String status, Instant beforeStartedAt, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + RUN_COLUMNS + " FROM integrity_runs WHERE true");
        if (mode != null) {
            sql.append(" AND mode = :mode");
        }
        if (status != null) {
            sql.append(" AND status = :status");
        }
        if (beforeStartedAt != null) {
            sql.append(" AND (started_at, id) < (:at, :beforeId)");
        }
        sql.append(" ORDER BY started_at DESC, id DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (mode != null) {
            spec = spec.param("mode", mode);
        }
        if (status != null) {
            spec = spec.param("status", status);
        }
        if (beforeStartedAt != null) {
            spec = spec.param("at", Timestamp.from(beforeStartedAt)).param("beforeId", beforeId);
        }
        return spec.query(RUN).list();
    }

    // --- bulgular ---

    void insertFindings(UUID runId, List<Finding> findings) {
        for (Finding f : findings) {
            jdbc.sql("INSERT INTO integrity_findings (run_id, type, severity, account_code, shipment_id, "
                            + "transaction_id, expected_minor, actual_minor, detail) VALUES (:run, :type, :severity, "
                            + ":account, :shipment, :tx, :expected, :actual, CAST(:detail AS jsonb))")
                    .param("run", runId).param("type", f.type()).param("severity", f.severity())
                    .param("account", f.accountCode()).param("shipment", f.shipmentId(), java.sql.Types.OTHER)
                    .param("tx", f.transactionId(), java.sql.Types.OTHER)
                    .param("expected", f.expectedMinor(), java.sql.Types.BIGINT)
                    .param("actual", f.actualMinor(), java.sql.Types.BIGINT)
                    .param("detail", jsonMapper.writeValueAsString(f.detail() == null ? Map.of() : f.detail()))
                    .update();
        }
    }

    List<StoredFinding> listFindings(UUID runId, String type, Long beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, run_id, type, severity, account_code, shipment_id, "
                + "transaction_id, expected_minor, actual_minor, detail::text AS detail, created_at "
                + "FROM integrity_findings WHERE true");
        if (runId != null) {
            sql.append(" AND run_id = :run");
        }
        if (type != null) {
            sql.append(" AND type = :type");
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
        if (beforeId != null) {
            spec = spec.param("before", beforeId);
        }
        return spec.query((rs, n) -> new StoredFinding(rs.getLong("id"), rs.getObject("run_id", UUID.class),
                rs.getString("type"), rs.getString("severity"), rs.getString("account_code"),
                rs.getObject("shipment_id", UUID.class), rs.getObject("transaction_id", UUID.class),
                (Long) rs.getObject("expected_minor"), (Long) rs.getObject("actual_minor"), rs.getString("detail"),
                instant(rs.getObject("created_at", OffsetDateTime.class)))).list();
    }

    Map<String, Integer> findingTypeCounts(UUID runId) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        jdbc.sql("SELECT type, count(*) AS n FROM integrity_findings WHERE run_id = :run GROUP BY type")
                .param("run", runId)
                .query((RowCallbackHandler) rs -> counts.put(rs.getString("type"), rs.getInt("n")));
        return counts;
    }

    // --- checkpoint'ler ---

    Optional<Checkpoint> latestCheckpoint() {
        return jdbc.sql("SELECT id, run_id, taken_at, last_entry_id, entry_count, total_debit_minor, "
                        + "total_credit_minor, chain_hash FROM ledger_checkpoints ORDER BY id DESC LIMIT 1")
                .query(CHECKPOINT).optional();
    }

    List<Checkpoint> checkpointsAscending() {
        return jdbc.sql("SELECT id, run_id, taken_at, last_entry_id, entry_count, total_debit_minor, "
                        + "total_credit_minor, chain_hash FROM ledger_checkpoints ORDER BY id")
                .query(CHECKPOINT).list();
    }

    List<Checkpoint> listCheckpoints(Long beforeId, int limit) {
        return jdbc.sql("SELECT id, run_id, taken_at, last_entry_id, entry_count, total_debit_minor, "
                        + "total_credit_minor, chain_hash FROM ledger_checkpoints "
                        + "WHERE id < COALESCE(:before, 9223372036854775807) ORDER BY id DESC LIMIT :limit")
                .param("before", beforeId, java.sql.Types.BIGINT).param("limit", limit)
                .query(CHECKPOINT).list();
    }

    /** Henüz bir checkpoint'in kapsamadığı kayıt sayısı: {@code max(id) - son checkpoint'in last_entry_id}. */
    long checkpointLag() {
        return jdbc.sql("SELECT COALESCE((SELECT max(id) FROM ledger_entries), 0) "
                        + "- COALESCE((SELECT last_entry_id FROM ledger_checkpoints ORDER BY id DESC LIMIT 1), 0)")
                .query(Long.class).single();
    }

    void insertCheckpoint(UUID runId, long lastEntryId, long entryCount, long debit, long credit, String hash) {
        jdbc.sql("INSERT INTO ledger_checkpoints (run_id, last_entry_id, entry_count, total_debit_minor, "
                        + "total_credit_minor, chain_hash) VALUES (:run, :last, :count, :debit, :credit, :hash)")
                .param("run", runId).param("last", lastEntryId).param("count", entryCount).param("debit", debit)
                .param("credit", credit).param("hash", hash)
                .update();
    }

    // --- kilit ---

    void setLockTimeoutMillis(long millis) {
        jdbc.sql("SELECT set_config('lock_timeout', :value, true)").param("value", millis + "ms")
                .query().listOfRows();
    }

    void lockEntriesGateExclusive() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", LedgerRepository.ENTRIES_GATE).query().listOfRows();
    }

    // --- defter taraması ---

    long maxEntryId() {
        return jdbc.sql("SELECT COALESCE(max(id), 0) FROM ledger_entries").query(Long.class).single();
    }

    /** İşlemler bölünmesin: {@code target}'ta kesilen bir işlemin tüm kayıtlarını da kapsayan en küçük üst sınır. */
    long extendToWholeTransactions(long fromExclusive, long target) {
        return jdbc.sql("SELECT COALESCE(max(e2.id), :target) FROM ledger_entries e2 WHERE e2.transaction_id IN "
                        + "(SELECT transaction_id FROM ledger_entries WHERE id > :from AND id <= :target)")
                .param("from", fromExclusive).param("target", target).query(Long.class).single();
    }

    RangeTotals rangeTotals(long fromExclusive, long toInclusive) {
        return jdbc.sql("SELECT count(*) AS n, COALESCE(sum(CASE direction WHEN 'DEBIT' THEN amount_minor END), 0) "
                        + "AS debit, COALESCE(sum(CASE direction WHEN 'CREDIT' THEN amount_minor END), 0) AS credit "
                        + "FROM ledger_entries WHERE id > :from AND id <= :to")
                .param("from", fromExclusive).param("to", toInclusive)
                .query((rs, n) -> new RangeTotals(rs.getLong("n"), rs.getLong("debit"), rs.getLong("credit")))
                .single();
    }

    void streamEntries(long fromExclusive, long toInclusive, EntryVisitor visitor) {
        jdbc.sql("SELECT id, transaction_id, account_id, direction, amount_minor FROM ledger_entries "
                        + "WHERE id > :from AND id <= :to ORDER BY id")
                .param("from", fromExclusive).param("to", toInclusive)
                .query((RowCallbackHandler) rs -> visitor.visit(rs.getLong("id"),
                        rs.getObject("transaction_id", UUID.class), rs.getObject("account_id", UUID.class),
                        rs.getString("direction"), rs.getLong("amount_minor")));
    }

    interface EntryVisitor {
        void visit(long entryId, UUID transactionId, UUID accountId, String direction, long amountMinor);
    }

    List<BalanceRow> rangeBalances(long fromExclusive, long toInclusive) {
        return jdbc.sql("SELECT e.account_id, a.code, a.type, CASE WHEN a.code = 'LIABILITY:ESCROW' THEN "
                        + "COALESCE(t.shipment_id, CAST(:none AS uuid)) ELSE CAST(:none AS uuid) END AS scope, "
                        + "SUM(" + SIGNED + ") AS net FROM ledger_entries e "
                        + "JOIN accounts a ON a.id = e.account_id JOIN journal_transactions t ON t.id = e.transaction_id "
                        + "WHERE e.id > :from AND e.id <= :to GROUP BY e.account_id, a.code, a.type, scope")
                .param("none", NO_SCOPE.toString()).param("from", fromExclusive).param("to", toInclusive)
                .query(BALANCE).list();
    }

    record Unbalanced(UUID transactionId, long netDebit) {
    }

    List<Unbalanced> unbalancedTransactions(long fromExclusive, long toInclusive) {
        return jdbc.sql("SELECT e.transaction_id, SUM(" + SIGNED + ") AS net FROM ledger_entries e "
                        + "WHERE e.id > :from AND e.id <= :to GROUP BY e.transaction_id HAVING SUM(" + SIGNED
                        + ") <> 0")
                .param("from", fromExclusive).param("to", toInclusive)
                .query((rs, n) -> new Unbalanced(rs.getObject("transaction_id", UUID.class), rs.getLong("net")))
                .list();
    }

    List<UUID> reversalMismatches(long fromExclusive, long toInclusive) {
        return jdbc.sql("SELECT r.id FROM journal_transactions r WHERE r.reversal_of IS NOT NULL AND r.id IN "
                        + "(SELECT DISTINCT transaction_id FROM ledger_entries WHERE id > :from AND id <= :to) AND ("
                        + "(SELECT COALESCE(array_agg(x ORDER BY x), '{}') FROM (SELECT o.account_id::text || ':' || "
                        + "CASE o.direction WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END || ':' || o.amount_minor AS x "
                        + "FROM ledger_entries o WHERE o.transaction_id = r.reversal_of) q) "
                        + "IS DISTINCT FROM "
                        + "(SELECT COALESCE(array_agg(x ORDER BY x), '{}') FROM (SELECT m.account_id::text || ':' || "
                        + "m.direction || ':' || m.amount_minor AS x FROM ledger_entries m "
                        + "WHERE m.transaction_id = r.id) q2) "
                        + "OR r.shipment_id IS DISTINCT FROM "
                        + "(SELECT shipment_id FROM journal_transactions WHERE id = r.reversal_of))")
                .param("from", fromExclusive).param("to", toInclusive)
                .query((rs, n) -> rs.getObject("id", UUID.class)).list();
    }

    // --- bakiye önbelleği ---

    /** Delta'yı önbelleğe ekler, yeni net değeri döner. */
    long addToCache(BalanceRow delta) {
        return jdbc.sql("INSERT INTO ledger_balance_cache (account_id, scope_shipment_id, net_debit_minor) "
                        + "VALUES (:account, :scope, :net) ON CONFLICT (account_id, scope_shipment_id) DO UPDATE "
                        + "SET net_debit_minor = ledger_balance_cache.net_debit_minor + EXCLUDED.net_debit_minor "
                        + "RETURNING net_debit_minor")
                .param("account", delta.accountId()).param("scope", delta.scopeShipmentId())
                .param("net", delta.netDebit())
                .query(Long.class).single();
    }

    void deleteZeroCacheRows() {
        jdbc.sql("DELETE FROM ledger_balance_cache WHERE net_debit_minor = 0").update();
    }

    List<BalanceRow> cacheRows() {
        return jdbc.sql("SELECT c.account_id, a.code, a.type, c.scope_shipment_id AS scope, c.net_debit_minor AS net "
                        + "FROM ledger_balance_cache c JOIN accounts a ON a.id = c.account_id")
                .query(BALANCE).list();
    }

    /** Önbellekte eksiye düşmüş (borcu alacağı aşan) yükümlülük hesapları. */
    List<BalanceRow> negativeLiabilities(int limit) {
        return jdbc.sql("SELECT c.account_id, a.code, a.type, c.scope_shipment_id AS scope, c.net_debit_minor AS net "
                        + "FROM ledger_balance_cache c JOIN accounts a ON a.id = c.account_id "
                        + "WHERE a.type = 'LIABILITY' AND c.net_debit_minor > 0 ORDER BY c.net_debit_minor DESC "
                        + "LIMIT :limit")
                .param("limit", limit).query(BALANCE).list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
