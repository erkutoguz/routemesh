package com.routemesh.shipmentservice;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.grpc.server.port=0")
class ShipmentSchemaTest {

    @Autowired
    private PostgreSQLContainer postgres;

    private JdbcTemplate owner;
    private JdbcTemplate app;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        app = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), "shipment_app", "shipment_app_test"));
    }

    @Test
    void statusesAndTransitionsAreSeeded() {
        assertThat(count("shipment_statuses")).isEqualTo(13);
        assertThat(count("shipment_transitions")).isEqualTo(31);
        assertThat(count("shipment_transitions WHERE requires_pod")).isEqualTo(1);
    }

    @Test
    void appRoleCanRunGeographyProximityChecks() {
        // 0,00085 enlem derecesi ~94,5 m: 100 m yarıçapta içeride, 50 m yarıçapta dışarıda
        String within = "SELECT ST_DWithin(ST_SetSRID(ST_MakePoint(29.0, 41.00085), 4326)::geography, "
                + "ST_SetSRID(ST_MakePoint(29.0, 41.0), 4326)::geography, ?)";

        assertThat(app.queryForObject(within, Boolean.class, 100)).isTrue();
        assertThat(app.queryForObject(within, Boolean.class, 50)).isFalse();
    }

    @Test
    void terminalStatusesHaveNoOutgoingTransitions() {
        Integer outgoing = owner.queryForObject(
                "SELECT count(*) FROM shipment_transitions t JOIN shipment_statuses s ON s.code = t.from_status "
                        + "WHERE s.terminal", Integer.class);

        assertThat(outgoing).isZero();
    }

    @Test
    void illegalStatusUpdate_isRejectedByTrigger() {
        UUID id = insertShipment("PENDING_PAYMENT", null);

        assertThatThrownBy(() -> owner.update("UPDATE shipments SET status = 'DELIVERED' WHERE id = ?", id))
                .hasStackTraceContaining("transition from PENDING_PAYMENT to DELIVERED is not allowed");
    }

    @Test
    void legalStatusUpdate_isAccepted() {
        UUID id = insertShipment("PENDING_PAYMENT", null);

        owner.update("UPDATE shipments SET status = 'PAID' WHERE id = ?", id);

        assertThat(owner.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, id))
                .isEqualTo("PAID");
    }

    @Test
    void shipmentsCannotBeDeletedOrTruncated() {
        UUID id = insertShipment("PENDING_PAYMENT", null);

        assertThatThrownBy(() -> owner.update("DELETE FROM shipments WHERE id = ?", id))
                .hasStackTraceContaining("DELETE on shipments is not allowed");
        assertThatThrownBy(() -> owner.execute("TRUNCATE shipments"))
                .hasStackTraceContaining("cannot truncate a table referenced in a foreign key constraint");
        assertThatThrownBy(() -> owner.execute("TRUNCATE shipments CASCADE"))
                .hasStackTraceContaining("is not allowed");
    }

    @Test
    void statusHistoryIsImmutable() {
        UUID id = insertShipment("PENDING_PAYMENT", null);
        owner.update("INSERT INTO shipment_status_history (shipment_id, transition_key, request_hash, to_status, "
                + "actor, version) VALUES (?, 'k', 'h', 'PENDING_PAYMENT', 'MERCHANT', 0)", id);

        assertThatThrownBy(() -> owner.update("UPDATE shipment_status_history SET reason = 'x'"))
                .hasStackTraceContaining("UPDATE on shipment_status_history is not allowed");
        assertThatThrownBy(() -> owner.update("DELETE FROM shipment_status_history"))
                .hasStackTraceContaining("DELETE on shipment_status_history is not allowed");
        assertThatThrownBy(() -> owner.execute("TRUNCATE shipment_status_history"))
                .hasStackTraceContaining("TRUNCATE on shipment_status_history is not allowed");
    }

    @Test
    void appRole_canOnlyUpdateStatusColumns() {
        UUID id = insertShipment("PENDING_PAYMENT", null);

        assertThatThrownBy(() -> app.update("UPDATE shipments SET recipient_name = 'x' WHERE id = ?", id))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("UPDATE shipments SET final_amount_minor = 1 WHERE id = ?", id))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM shipments WHERE id = ?", id))
                .hasStackTraceContaining("permission denied");

        app.update("UPDATE shipments SET status = 'PAID', version = version + 1, updated_at = now() WHERE id = ?", id);
        assertThat(owner.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, id))
                .isEqualTo("PAID");
    }

    @Test
    void appRole_cannotTouchReferenceDataOrProofOfDelivery() {
        UUID id = insertShipment("PENDING_PAYMENT", null);

        assertThatThrownBy(() -> app.update("INSERT INTO shipment_transitions VALUES ('PAID', 'LOST', 'MERCHANT', false)"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM shipment_transitions"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("INSERT INTO proof_of_delivery (shipment_id) VALUES (?)", id))
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void courierIsRequiredWhenAssignedOrOutForDelivery() {
        assertThatThrownBy(() -> insertShipment("ASSIGNED", null))
                .hasStackTraceContaining("shipments_courier_assigned_check");
        assertThatThrownBy(() -> insertShipment("OUT_FOR_DELIVERY", null))
                .hasStackTraceContaining("shipments_courier_assigned_check");
    }

    @Test
    void courierIsNotAllowedBeforeAssignment() {
        assertThatThrownBy(() -> insertShipment("PAID", UUID.randomUUID()))
                .hasStackTraceContaining("shipments_courier_unassigned_check");
    }

    @Test
    void invalidValuesAreRejected() {
        assertThatThrownBy(() -> owner.update(insertSql("final_amount_minor", "0")))
                .hasStackTraceContaining("shipments_amounts_check");
        assertThatThrownBy(() -> owner.update(insertSql("currency", "'USD'")))
                .hasStackTraceContaining("shipments_currency_check");
        assertThatThrownBy(() -> owner.update(insertSql("delivery_latitude", "91")))
                .hasStackTraceContaining("shipments_latitude_check");
        assertThatThrownBy(() -> owner.update(insertSql("delivery_longitude", "181")))
                .hasStackTraceContaining("shipments_longitude_check");
        assertThatThrownBy(() -> owner.update(insertSql("status", "'UNKNOWN'")))
                .hasStackTraceContaining("violates foreign key constraint");
    }

    @Test
    void idempotencyKeyIsUnique() {
        String key = UUID.randomUUID().toString();
        owner.update(insertSql("idempotency_key", "'" + key + "'"));

        assertThatThrownBy(() -> owner.update(insertSql("idempotency_key", "'" + key + "'")))
                .hasStackTraceContaining("duplicate key");
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
        assertThat(owner.queryForObject("SELECT has_table_privilege('debezium', 'shipments', 'SELECT')",
                Boolean.class)).isFalse();
    }

    @Test
    void appRole_canDeleteOutboxEventsButNotUpdateThem() {
        UUID id = UUID.randomUUID();
        app.update("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, type, payload) "
                + "VALUES (?, 'test', 'x', 'T', '{}'::jsonb)", id);

        assertThatThrownBy(() -> app.update("UPDATE outbox_events SET type = 'Y' WHERE id = ?", id))
                .hasStackTraceContaining("permission denied");
        assertThat(app.update("DELETE FROM outbox_events WHERE id = ?", id)).isEqualTo(1);
    }

    private UUID insertShipment(String status, UUID courierId) {
        return owner.queryForObject(
                "INSERT INTO shipments (idempotency_key, request_hash, merchant_id, status, courier_id, "
                        + "recipient_name, recipient_phone, delivery_address, delivery_latitude, "
                        + "delivery_longitude, quote_id, final_amount_minor, base_price_minor, currency) "
                        + "VALUES (?, 'h', gen_random_uuid(), ?, ?, 'Ada', '+905551112233', 'Addr', 41.0, 29.0, "
                        + "gen_random_uuid(), 1001, 800, 'TRY') RETURNING id",
                UUID.class, UUID.randomUUID().toString(), status, courierId);
    }

    private String insertSql(String overriddenColumn, String overriddenValue) {
        String[] columns = {"idempotency_key", "status", "delivery_latitude", "delivery_longitude",
                "final_amount_minor", "currency"};
        String[] defaults = {"'" + UUID.randomUUID() + "'", "'PENDING_PAYMENT'", "41.0", "29.0", "1001", "'TRY'"};
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equals(overriddenColumn)) {
                defaults[i] = overriddenValue;
            }
        }
        return "INSERT INTO shipments (idempotency_key, request_hash, merchant_id, status, recipient_name, "
                + "recipient_phone, delivery_address, delivery_latitude, delivery_longitude, quote_id, "
                + "final_amount_minor, base_price_minor, currency) VALUES (" + defaults[0] + ", 'h', "
                + "gen_random_uuid(), " + defaults[1] + ", 'Ada', '+905551112233', 'Addr', " + defaults[2]
                + ", " + defaults[3] + ", gen_random_uuid(), " + defaults[4] + ", 800, " + defaults[5] + ")";
    }

    private int count(String fromAndWhere) {
        return owner.queryForObject("SELECT count(*) FROM " + fromAndWhere, Integer.class);
    }
}
