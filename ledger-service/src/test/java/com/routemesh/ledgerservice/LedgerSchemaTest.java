package com.routemesh.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.grpc.server.port=0")
class LedgerSchemaTest {

    @Autowired
    private PostgreSQLContainer postgres;

    private JdbcTemplate owner;
    private JdbcTemplate app;
    private TransactionTemplate appTransaction;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ownerDataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        DriverManagerDataSource appDataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), "ledger_app", "ledger_app_test");
        owner = new JdbcTemplate(ownerDataSource);
        app = new JdbcTemplate(appDataSource);
        appTransaction = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
    }

    @Test
    void systemAccountsAreSeeded() {
        Integer count = app.queryForObject(
                "SELECT count(*) FROM accounts WHERE code IN "
                        + "('ASSET:PSP_CLEARING', 'LIABILITY:ESCROW', 'REVENUE:PLATFORM_FEE', 'EXPENSE:LOST_COMPENSATION')",
                Integer.class);

        assertThat(count).isEqualTo(4);
    }

    @Test
    void balancedTransaction_isCommitted() {
        UUID transactionId = postBalancedTransaction(1001);

        Integer entries = app.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", Integer.class, transactionId);

        assertThat(entries).isEqualTo(2);
    }

    @Test
    void unbalancedTransaction_isRejectedAtCommit() {
        assertThatThrownBy(() -> appTransaction.executeWithoutResult(status -> {
            UUID transactionId = insertTransaction(newKey());
            insertEntry(transactionId, "ASSET:PSP_CLEARING", "DEBIT", 1001);
            insertEntry(transactionId, "LIABILITY:ESCROW", "CREDIT", 1000);
        })).hasStackTraceContaining("is not balanced");
    }

    @Test
    void transactionWithoutEntries_isRejectedAtCommit() {
        assertThatThrownBy(() -> appTransaction.executeWithoutResult(status -> insertTransaction(newKey())))
                .hasStackTraceContaining("has no entries");
    }

    @Test
    void entries_cannotBeUpdatedOrDeletedEvenByOwner() {
        postBalancedTransaction(500);

        assertThatThrownBy(() -> owner.update("UPDATE ledger_entries SET amount_minor = 1"))
                .hasStackTraceContaining("ledger records are immutable");
        assertThatThrownBy(() -> owner.update("DELETE FROM ledger_entries"))
                .hasStackTraceContaining("ledger records are immutable");
        assertThatThrownBy(() -> owner.execute("TRUNCATE ledger_entries"))
                .hasStackTraceContaining("ledger records are immutable");
    }

    @Test
    void appRole_hasNoUpdateDeleteOrTruncatePrivileges() {
        postBalancedTransaction(500);

        assertThatThrownBy(() -> app.update("UPDATE ledger_entries SET amount_minor = 1"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM journal_transactions"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.execute("TRUNCATE accounts"))
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void zeroAmount_isRejected() {
        assertThatThrownBy(() -> appTransaction.executeWithoutResult(status -> {
            UUID transactionId = insertTransaction(newKey());
            insertEntry(transactionId, "ASSET:PSP_CLEARING", "DEBIT", 0);
        })).hasStackTraceContaining("ledger_entries_amount_check");
    }

    @Test
    void nonTryAccount_isRejected() {
        assertThatThrownBy(() -> app.update(
                "INSERT INTO accounts (code, type, currency) VALUES ('LIABILITY:MERCHANT_WALLET:usd', 'LIABILITY', 'USD')"))
                .hasStackTraceContaining("accounts_currency_check");
    }

    @Test
    void accountCodeMustMatchItsType() {
        assertThatThrownBy(() -> app.update(
                "INSERT INTO accounts (code, type, currency) VALUES ('REVENUE:WRONG', 'LIABILITY', 'TRY')"))
                .hasStackTraceContaining("accounts_code_prefix_check");
    }

    @Test
    void duplicateIdempotencyKey_isRejected() {
        String key = newKey();
        postBalancedTransaction(key, 100);

        assertThatThrownBy(() -> postBalancedTransaction(key, 100))
                .hasStackTraceContaining("duplicate key");
    }

    @Test
    void transactionCanBeReversedOnlyOnce() {
        UUID original = postBalancedTransaction(100);

        postReversal(original);

        assertThatThrownBy(() -> postReversal(original)).hasStackTraceContaining("duplicate key");
    }

    private void postReversal(UUID original) {
        appTransaction.executeWithoutResult(status -> {
            UUID reversalId = app.queryForObject(
                    "INSERT INTO journal_transactions (idempotency_key, request_hash, transaction_type, reversal_of) "
                            + "VALUES (?, 'hash', 'REVERSAL', ?) RETURNING id",
                    UUID.class, newKey(), original);
            insertEntry(reversalId, "ASSET:PSP_CLEARING", "CREDIT", 100);
            insertEntry(reversalId, "LIABILITY:ESCROW", "DEBIT", 100);
        });
    }

    @Test
    void outboxPublication_publishesOnlyInserts() {
        Map<String, Object> publication = owner.queryForMap(
                "SELECT pubinsert, pubupdate, pubdelete, pubtruncate FROM pg_publication "
                        + "WHERE pubname = 'outbox_publication'");

        assertThat(publication).containsEntry("pubinsert", true).containsEntry("pubupdate", false)
                .containsEntry("pubdelete", false).containsEntry("pubtruncate", false);
        assertThat(owner.queryForList(
                "SELECT tablename FROM pg_publication_tables WHERE pubname = 'outbox_publication'", String.class))
                .containsExactly("outbox_events");
    }

    @Test
    void debeziumRole_canReplicateAndReadOnlyTheOutbox() {
        assertThat(owner.queryForObject("SELECT rolreplication FROM pg_roles WHERE rolname = 'debezium'",
                Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT has_table_privilege('debezium', 'outbox_events', 'SELECT')",
                Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT has_table_privilege('debezium', 'ledger_entries', 'SELECT')",
                Boolean.class)).isFalse();
    }

    @Test
    void appRole_canInsertAndDeleteOutboxEventsButNotUpdateThem() {
        UUID id = UUID.randomUUID();
        app.update("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, type, payload) "
                + "VALUES (?, 'test', 'x', 'T', '{}'::jsonb)", id);

        assertThatThrownBy(() -> app.update("UPDATE outbox_events SET type = 'Y' WHERE id = ?", id))
                .hasStackTraceContaining("permission denied");
        assertThat(app.update("DELETE FROM outbox_events WHERE id = ?", id)).isEqualTo(1);
    }

    private UUID postBalancedTransaction(long amountMinor) {
        return postBalancedTransaction(newKey(), amountMinor);
    }

    private UUID postBalancedTransaction(String key, long amountMinor) {
        return appTransaction.execute(status -> {
            UUID transactionId = insertTransaction(key);
            insertEntry(transactionId, "ASSET:PSP_CLEARING", "DEBIT", amountMinor);
            insertEntry(transactionId, "LIABILITY:ESCROW", "CREDIT", amountMinor);
            return transactionId;
        });
    }

    private UUID insertTransaction(String key) {
        return app.queryForObject(
                "INSERT INTO journal_transactions (idempotency_key, request_hash, transaction_type, shipment_id) "
                        + "VALUES (?, 'hash', 'TEST', ?) RETURNING id",
                UUID.class, key, UUID.randomUUID());
    }

    private void insertEntry(UUID transactionId, String accountCode, String direction, long amountMinor) {
        app.update(
                "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount_minor) "
                        + "VALUES (?, (SELECT id FROM accounts WHERE code = ?), ?, ?)",
                transactionId, accountCode, direction, amountMinor);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
