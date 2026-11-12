package com.routemesh.ledgerservice.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.routemesh.ledgerservice.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"spring.grpc.server.port=0", "routemesh.ledger.integrity.scheduler-enabled=false"})
class IntegritySchemaTest {

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcTemplate owner;

    private Connection app;
    private UUID runId;

    @BeforeEach
    void connect() throws SQLException {
        app = DriverManager.getConnection(postgres.getJdbcUrl(), "ledger_app", "ledger_app_test");
        runId = UUID.randomUUID();
        exec("INSERT INTO integrity_runs (id, mode, trigger_source) VALUES ('" + runId + "', 'FULL', 'MANUAL')");
    }

    @AfterEach
    void close() throws SQLException {
        exec("UPDATE integrity_runs SET status = 'FAILED', finished_at = now() WHERE status = 'RUNNING'");
        app.close();
    }

    @Test
    void findingsAndCheckpointsAreAppendOnly() throws SQLException {
        exec("INSERT INTO integrity_findings (run_id, type, severity) VALUES ('" + runId + "', 'X', 'CRITICAL')");
        exec("INSERT INTO ledger_checkpoints (run_id, last_entry_id, entry_count, total_debit_minor, "
                + "total_credit_minor, chain_hash) VALUES ('" + runId + "', " + System.nanoTime() + ", 1, 1, 1, '"
                + "a".repeat(64) + "')");

        for (String statement : new String[] {"UPDATE integrity_findings SET type = 'Y'", "DELETE FROM integrity_findings",
                "UPDATE ledger_checkpoints SET entry_count = 2", "DELETE FROM ledger_checkpoints"}) {
            assertThatThrownBy(() -> exec(statement)).as(statement).hasMessageContaining("permission denied");
        }
    }

    @Test
    void theTriggersAlsoStopAPrivilegedRoleFromRewritingFindingsAndCheckpoints() {
        owner.update("INSERT INTO integrity_findings (run_id, type, severity) VALUES (?, 'X', 'CRITICAL')", runId);

        assertThatThrownBy(() -> owner.update("UPDATE integrity_findings SET type = 'Y'"))
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> owner.update("DELETE FROM integrity_runs WHERE id = ?", runId))
                .hasMessageContaining("not allowed");
    }

    @Test
    void aRunRecordOnlyAllowsTheStatusColumnsToChangeAndNeverBeDeleted() throws SQLException {
        assertThat(exec("UPDATE integrity_runs SET status = 'OK', finished_at = now(), entries_scanned = 5 "
                + "WHERE id = '" + runId + "'")).isEqualTo(1);

        assertThatThrownBy(() -> exec("UPDATE integrity_runs SET mode = 'INCREMENTAL' WHERE id = '" + runId + "'"))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec("DELETE FROM integrity_runs")).hasMessageContaining("permission denied");
    }

    @Test
    void onlyOneRunPerModeCanBeRunningAndAFinishedRunMustHaveAFinishTime() throws SQLException {
        assertThatThrownBy(() -> exec("INSERT INTO integrity_runs (mode, trigger_source) VALUES ('FULL', 'SCHEDULED')"))
                .hasMessageContaining("integrity_runs_single_flight");
        exec("INSERT INTO integrity_runs (mode, trigger_source) VALUES ('INCREMENTAL', 'SCHEDULED')");
        assertThatThrownBy(() -> exec("INSERT INTO integrity_runs (mode, trigger_source, status) "
                + "VALUES ('FULL', 'MANUAL', 'OK')")).hasMessageContaining("integrity_runs_finished_check");
    }

    @Test
    void theBalanceCacheOnlyAllowsTheNetColumnToChange() throws SQLException {
        UUID account = owner.queryForObject("SELECT id FROM accounts WHERE code = 'ASSET:PSP_CLEARING'", UUID.class);
        exec("INSERT INTO ledger_balance_cache (account_id, net_debit_minor) VALUES ('" + account + "', 5)");

        assertThat(exec("UPDATE ledger_balance_cache SET net_debit_minor = 9 WHERE account_id = '" + account + "'"))
                .isEqualTo(1);
        assertThatThrownBy(() -> exec("UPDATE ledger_balance_cache SET scope_shipment_id = '"
                + UUID.randomUUID() + "'")).hasMessageContaining("permission denied");
        assertThat(exec("DELETE FROM ledger_balance_cache WHERE account_id = '" + account + "'")).isEqualTo(1);
    }

    @Test
    void aMalformedChainHashIsRejected() {
        assertThatThrownBy(() -> exec("INSERT INTO ledger_checkpoints (run_id, last_entry_id, entry_count, "
                + "total_debit_minor, total_credit_minor, chain_hash) VALUES ('" + runId + "', 999999999, 1, 1, 1, 'xyz')"))
                .hasMessageContaining("ledger_checkpoints_hash_check");
    }

    private int exec(String sql) throws SQLException {
        try (Statement statement = app.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }
}
