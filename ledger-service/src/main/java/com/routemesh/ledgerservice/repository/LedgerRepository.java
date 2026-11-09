package com.routemesh.ledgerservice.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.routemesh.ledgerservice.domain.AccountType;
import com.routemesh.ledgerservice.domain.Direction;

@Repository
public class LedgerRepository {

    public record Account(UUID id, String code, AccountType type) {
    }

    public record StoredTransaction(UUID id, String requestHash, Instant createdAt, UUID shipmentId) {
    }

    public record StoredEntry(String accountCode, Direction direction, long amountMinor) {
    }

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", key)
                .query()
                .listOfRows();
    }

    public Optional<StoredTransaction> findTransactionByKey(String idempotencyKey) {
        return jdbc.sql("SELECT id, request_hash, created_at, shipment_id FROM journal_transactions "
                        + "WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> new StoredTransaction(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("shipment_id", UUID.class)))
                .optional();
    }

    public Optional<StoredTransaction> findTransactionById(UUID id) {
        return jdbc.sql("SELECT id, request_hash, created_at, shipment_id FROM journal_transactions WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new StoredTransaction(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("shipment_id", UUID.class)))
                .optional();
    }

    public List<StoredEntry> findEntries(UUID transactionId) {
        return jdbc.sql("SELECT a.code, e.direction, e.amount_minor FROM ledger_entries e "
                        + "JOIN accounts a ON a.id = e.account_id WHERE e.transaction_id = :id")
                .param("id", transactionId)
                .query((rs, rowNum) -> new StoredEntry(
                        rs.getString("code"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getLong("amount_minor")))
                .list();
    }

    public Optional<StoredTransaction> insertTransaction(String idempotencyKey, String requestHash,
                                                         String transactionType, UUID shipmentId, UUID reversalOf) {
        return jdbc.sql("INSERT INTO journal_transactions "
                        + "(idempotency_key, request_hash, transaction_type, shipment_id, reversal_of) "
                        + "VALUES (:key, :hash, :type, :shipment, :reversalOf) "
                        + "ON CONFLICT (idempotency_key) DO NOTHING "
                        + "RETURNING id, request_hash, created_at, shipment_id")
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("type", transactionType)
                .param("shipment", shipmentId, java.sql.Types.OTHER)
                .param("reversalOf", reversalOf, java.sql.Types.OTHER)
                .query((rs, rowNum) -> new StoredTransaction(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("shipment_id", UUID.class)))
                .optional();
    }

    public boolean isReversed(UUID transactionId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM journal_transactions WHERE reversal_of = :id)")
                .param("id", transactionId)
                .query(Boolean.class)
                .single();
    }

    public void insertEntry(UUID transactionId, UUID accountId, Direction direction, long amountMinor) {
        jdbc.sql("INSERT INTO ledger_entries (transaction_id, account_id, direction, amount_minor) "
                        + "VALUES (:transaction, :account, :direction, :amount)")
                .param("transaction", transactionId)
                .param("account", accountId)
                .param("direction", direction.name())
                .param("amount", amountMinor)
                .update();
    }

    public Optional<Account> findAccount(String code) {
        return jdbc.sql("SELECT id, code, type FROM accounts WHERE code = :code")
                .param("code", code)
                .query((rs, rowNum) -> new Account(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        AccountType.valueOf(rs.getString("type"))))
                .optional();
    }

    public void createAccountIfAbsent(String code, AccountType type) {
        jdbc.sql("INSERT INTO accounts (code, type, currency) VALUES (:code, :type, 'TRY') "
                        + "ON CONFLICT (code) DO NOTHING")
                .param("code", code)
                .param("type", type.name())
                .update();
    }

    public long netDebit(UUID accountId, UUID shipmentId) {
        if (shipmentId == null) {
            return jdbc.sql("SELECT COALESCE(SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor "
                            + "ELSE -e.amount_minor END), 0) FROM ledger_entries e WHERE e.account_id = :account")
                    .param("account", accountId)
                    .query(Long.class)
                    .single();
        }
        return jdbc.sql("SELECT COALESCE(SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor "
                        + "ELSE -e.amount_minor END), 0) FROM ledger_entries e "
                        + "JOIN journal_transactions t ON t.id = e.transaction_id "
                        + "WHERE e.account_id = :account AND t.shipment_id = :shipment")
                .param("account", accountId)
                .param("shipment", shipmentId)
                .query(Long.class)
                .single();
    }
}
