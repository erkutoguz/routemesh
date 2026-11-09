package com.routemesh.paymentservice;

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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Import({TestcontainersConfiguration.class, TestPspConfiguration.class})
@SpringBootTest(properties = "spring.grpc.server.port=0")
class PaymentSchemaTest {

    @Autowired
    private PostgreSQLContainer postgres;

    private JdbcTemplate owner;
    private JdbcTemplate app;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        app = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), "payment_app", "payment_app_test"));
    }

    @Test
    void statusesAndTransitionsAreSeeded() {
        assertThat(count("payment_statuses")).isEqualTo(8);
        assertThat(count("payment_transitions")).isEqualTo(10);
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_transitions t JOIN payment_statuses s "
                + "ON s.code = t.from_status WHERE s.terminal", Integer.class)).isZero();
    }

    @Test
    void illegalStatusUpdate_isRejectedByTrigger() {
        UUID id = insertIntent("CREATED");

        assertThatThrownBy(() -> owner.update("UPDATE payment_intents SET status = 'CAPTURED' WHERE id = ?", id))
                .hasStackTraceContaining("payment transition from CREATED to CAPTURED is not allowed");
        assertThatThrownBy(() -> owner.update("UPDATE payment_intents SET status = 'REFUNDED' WHERE id = ?", id))
                .hasStackTraceContaining("is not allowed");

        owner.update("UPDATE payment_intents SET status = 'AUTHORIZED' WHERE id = ?", id);
        assertThat(owner.queryForObject("SELECT status FROM payment_intents WHERE id = ?", String.class, id))
                .isEqualTo("AUTHORIZED");
    }

    @Test
    void terminalStatuses_cannotBeLeft() {
        UUID id = insertIntent("CREATED");
        owner.update("UPDATE payment_intents SET status = 'FAILED' WHERE id = ?", id);

        assertThatThrownBy(() -> owner.update("UPDATE payment_intents SET status = 'AUTHORIZED' WHERE id = ?", id))
                .hasStackTraceContaining("is not allowed");
    }

    @Test
    void recordsCannotBeDeletedOrTruncated() {
        UUID id = insertIntent("CREATED");

        assertThatThrownBy(() -> owner.update("DELETE FROM payment_intents WHERE id = ?", id))
                .hasStackTraceContaining("DELETE on payment_intents is not allowed");
        assertThatThrownBy(() -> owner.execute("TRUNCATE payment_transactions CASCADE"))
                .hasStackTraceContaining("is not allowed");
        owner.update("INSERT INTO webhook_events (psp_event_id, event_type, payload) VALUES (?, 't', '{}')",
                "evt_" + UUID.randomUUID());
        assertThatThrownBy(() -> owner.update("DELETE FROM webhook_events"))
                .hasStackTraceContaining("DELETE on webhook_events is not allowed");
    }

    @Test
    void appRole_canOnlyUpdateTheStatusColumns() {
        UUID id = insertIntent("CREATED");

        assertThatThrownBy(() -> app.update("UPDATE payment_intents SET amount_minor = 1 WHERE id = ?", id))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("UPDATE payment_intents SET psp_payment_id = 'x' WHERE id = ?", id))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM payment_intents WHERE id = ?", id))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("UPDATE payment_transactions SET amount_minor = 1"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("UPDATE webhook_events SET payload = 'x'"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("INSERT INTO payment_transitions VALUES ('CREATED', 'CAPTURED')"))
                .hasStackTraceContaining("permission denied");

        app.update("UPDATE payment_intents SET status = 'AUTHORIZED', version = version + 1, updated_at = now() "
                + "WHERE id = ?", id);
        assertThat(owner.queryForObject("SELECT status FROM payment_intents WHERE id = ?", String.class, id))
                .isEqualTo("AUTHORIZED");
    }

    @Test
    void invalidValues_areRejected() {
        assertThatThrownBy(() -> insertIntent("CREATED", "amount_minor", "0"))
                .hasStackTraceContaining("payment_intents_amount_check");
        assertThatThrownBy(() -> insertIntent("CREATED", "currency", "'USD'"))
                .hasStackTraceContaining("payment_intents_currency_check");
        assertThatThrownBy(() -> insertIntent("CREATED", "captured_minor", "2000"))
                .hasStackTraceContaining("payment_intents_captured_check");
        assertThatThrownBy(() -> insertIntent("CREATED", "refunded_minor", "1"))
                .hasStackTraceContaining("payment_intents_refunded_check");
        assertThatThrownBy(() -> insertIntent("UNKNOWN"))
                .hasStackTraceContaining("violates foreign key constraint");
    }

    @Test
    void uniqueness_isEnforced() {
        UUID id = insertIntent("CREATED");
        String pspPaymentId = owner.queryForObject("SELECT psp_payment_id FROM payment_intents WHERE id = ?",
                String.class, id);

        assertThatThrownBy(() -> insertIntent("CREATED", "psp_payment_id", "'" + pspPaymentId + "'"))
                .hasStackTraceContaining("duplicate key");

        owner.update("INSERT INTO webhook_events (psp_event_id, event_type, payload) VALUES ('evt_dup', 't', '{}')");
        assertThatThrownBy(() -> owner.update(
                "INSERT INTO webhook_events (psp_event_id, event_type, payload) VALUES ('evt_dup', 't', '{}')"))
                .hasStackTraceContaining("duplicate key");
    }

    @Test
    void transactions_areUniquePerIntentTypeAndCommandKey_andValidated() {
        UUID id = insertIntent("AUTHORIZED");
        owner.update("INSERT INTO payment_transactions (intent_id, type, command_key, request_hash, amount_minor, "
                + "status) VALUES (?, 'CAPTURE', 'k1', 'h', 1001, 'PENDING')", id);

        assertThatThrownBy(() -> owner.update("INSERT INTO payment_transactions (intent_id, type, command_key, "
                + "request_hash, amount_minor, status) VALUES (?, 'CAPTURE', 'k1', 'h', 1001, 'PENDING')", id))
                .hasStackTraceContaining("payment_transactions_command_unique");
        assertThatThrownBy(() -> owner.update("INSERT INTO payment_transactions (intent_id, type, command_key, "
                + "request_hash, amount_minor, status) VALUES (?, 'CHARGE', 'k2', 'h', 1, 'PENDING')", id))
                .hasStackTraceContaining("payment_transactions_type_check");
        assertThatThrownBy(() -> owner.update("INSERT INTO payment_transactions (intent_id, type, command_key, "
                + "request_hash, amount_minor, status) VALUES (?, 'VOID', 'k3', 'h', 1, 'DONE')", id))
                .hasStackTraceContaining("payment_transactions_status_check");
    }

    @Test
    void outboxPublication_publishesOnlyInserts() {
        Map<String, Object> publication = owner.queryForMap("SELECT pubinsert, pubupdate, pubdelete, pubtruncate "
                + "FROM pg_publication WHERE pubname = 'outbox_publication'");

        assertThat(publication).containsEntry("pubinsert", true).containsEntry("pubupdate", false)
                .containsEntry("pubdelete", false).containsEntry("pubtruncate", false);
        assertThat(owner.queryForList("SELECT tablename FROM pg_publication_tables "
                + "WHERE pubname = 'outbox_publication'", String.class)).containsExactly("outbox_events");
        assertThat(owner.queryForObject("SELECT rolreplication FROM pg_roles WHERE rolname = 'debezium'",
                Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT has_table_privilege('debezium', 'outbox_events', 'SELECT')",
                Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT has_table_privilege('debezium', 'payment_intents', 'SELECT')",
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

    private UUID insertIntent(String status, String... override) {
        String column = override.length == 2 ? override[0] : "";
        String value = override.length == 2 ? override[1] : "";
        String pspId = v(column, "psp_payment_id", value, "'psp_" + UUID.randomUUID() + "'");
        return owner.queryForObject("INSERT INTO payment_intents (idempotency_key, request_hash, shipment_id, "
                + "amount_minor, currency, status, psp_payment_id, checkout_url, captured_minor, refunded_minor, "
                + "expires_at) VALUES ('" + UUID.randomUUID() + "', 'h', gen_random_uuid(), "
                + v(column, "amount_minor", value, "1001") + ", " + v(column, "currency", value, "'TRY'") + ", '"
                + status + "', " + pspId + ", 'https://x', " + v(column, "captured_minor", value, "0") + ", "
                + v(column, "refunded_minor", value, "0") + ", now() + interval '15 minutes') RETURNING id",
                UUID.class);
    }

    private static String v(String column, String name, String overrideValue, String defaultValue) {
        return column.equals(name) ? overrideValue : defaultValue;
    }

    private int count(String table) {
        return owner.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
