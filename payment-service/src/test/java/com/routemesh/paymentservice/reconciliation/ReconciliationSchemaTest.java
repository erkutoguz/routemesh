package com.routemesh.paymentservice.reconciliation;

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

import com.routemesh.paymentservice.TestPspConfiguration;
import com.routemesh.paymentservice.TestcontainersConfiguration;

@Import({TestcontainersConfiguration.class, TestPspConfiguration.class})
@SpringBootTest(properties = {"spring.grpc.server.port=0", "routemesh.payment.reconciliation.scheduler-enabled=false"})
class ReconciliationSchemaTest {

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcTemplate owner;

    private Connection app;
    private UUID runId;

    @BeforeEach
    void connect() throws SQLException {
        app = DriverManager.getConnection(postgres.getJdbcUrl(), "payment_app", "payment_app_test");
        exec("UPDATE reconciliation_runs SET status = 'FAILED', finished_at = now() WHERE status = 'RUNNING'");
        runId = UUID.randomUUID();
        exec("INSERT INTO reconciliation_runs (id, trigger_source, window_from, window_to) VALUES ('" + runId
                + "', 'MANUAL', now() - interval '1 hour', now())");
    }

    @AfterEach
    void close() throws SQLException {
        exec("UPDATE reconciliation_runs SET status = 'FAILED', finished_at = now() WHERE status = 'RUNNING'");
        app.close();
    }

    @Test
    void findingsAreAppendOnlyForTheApplicationRoleAndForTheOwnerToo() throws SQLException {
        exec("INSERT INTO reconciliation_findings (run_id, type, severity) VALUES ('" + runId
                + "', 'STATUS_MISMATCH', 'CRITICAL')");

        for (String statement : new String[] {"UPDATE reconciliation_findings SET type = 'X'",
                "DELETE FROM reconciliation_findings"}) {
            assertThatThrownBy(() -> exec(statement)).as(statement).hasMessageContaining("permission denied");
        }
        assertThatThrownBy(() -> owner.update("UPDATE reconciliation_findings SET type = 'X'"))
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> owner.update("DELETE FROM reconciliation_runs WHERE id = ?", runId))
                .hasMessageContaining("not allowed");
    }

    @Test
    void aRunOnlyAllowsItsResultColumnsToChangeAndCanNeverBeDeleted() throws SQLException {
        assertThat(exec("UPDATE reconciliation_runs SET status = 'OK', finished_at = now(), findings_count = 3 "
                + "WHERE id = '" + runId + "'")).isEqualTo(1);

        assertThatThrownBy(() -> exec("UPDATE reconciliation_runs SET window_from = now() WHERE id = '" + runId + "'"))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec("DELETE FROM reconciliation_runs")).hasMessageContaining("permission denied");
    }

    @Test
    void onlyOneRunCanBeRunningAndAFinishedRunNeedsAFinishTime() {
        assertThatThrownBy(() -> exec("INSERT INTO reconciliation_runs (trigger_source, window_from, window_to) "
                + "VALUES ('SCHEDULED', now() - interval '1 hour', now())"))
                .hasMessageContaining("reconciliation_runs_single_flight");
        assertThatThrownBy(() -> exec("INSERT INTO reconciliation_runs (trigger_source, status, window_from, window_to) "
                + "VALUES ('SCHEDULED', 'OK', now() - interval '1 hour', now())"))
                .hasMessageContaining("reconciliation_runs_finished_check");
    }

    @Test
    void theWindowMustBeAProperRangeAndTheEnumsAreChecked() {
        assertThatThrownBy(() -> exec("INSERT INTO reconciliation_runs (trigger_source, status, finished_at, "
                + "window_from, window_to) VALUES ('MANUAL', 'OK', now(), now(), now())"))
                .hasMessageContaining("reconciliation_runs_window_check");
        assertThatThrownBy(() -> exec("INSERT INTO reconciliation_runs (trigger_source, status, finished_at, "
                + "window_from, window_to) VALUES ('CRON', 'OK', now(), now() - interval '1 hour', now())"))
                .hasMessageContaining("reconciliation_runs_trigger_check");
        assertThatThrownBy(() -> exec("INSERT INTO reconciliation_findings (run_id, type, severity) VALUES ('"
                + runId + "', 'X', 'FATAL')")).hasMessageContaining("reconciliation_findings_severity_check");
    }

    private int exec(String sql) throws SQLException {
        try (Statement statement = app.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }
}
