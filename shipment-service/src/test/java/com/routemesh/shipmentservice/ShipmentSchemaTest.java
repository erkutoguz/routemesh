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

    // --- teslimat kanıtı (V5) ---

    @Test
    void proofOfDelivery_isRejectedBeyondFiftyMeters() {
        UUID shipment = insertShipment("OUT_FOR_DELIVERY", UUID.randomUUID());
        UUID photo = insertFile(shipment, "PHOTO");

        assertThat(insertProof(shipment, photo, 50)).isEqualTo(1);
        UUID other = insertShipment("OUT_FOR_DELIVERY", UUID.randomUUID());
        UUID otherPhoto = insertFile(other, "PHOTO");
        assertThatThrownBy(() -> insertProof(other, otherPhoto, 50.1))
                .hasStackTraceContaining("proof_of_delivery_distance_check");
    }

    @Test
    void proofOfDelivery_filesMustBelongToTheShipmentAndHaveTheRightKind() {
        UUID shipment = insertShipment("OUT_FOR_DELIVERY", UUID.randomUUID());
        UUID elsewhere = insertShipment("OUT_FOR_DELIVERY", UUID.randomUUID());
        UUID signature = insertFile(shipment, "SIGNATURE");
        UUID foreignPhoto = insertFile(elsewhere, "PHOTO");

        assertThatThrownBy(() -> insertProof(shipment, signature, 5))
                .hasStackTraceContaining("photo_file_id must reference a PHOTO file");
        assertThatThrownBy(() -> insertProof(shipment, foreignPhoto, 5))
                .hasStackTraceContaining("proof_of_delivery_photo_fk");
    }

    @Test
    void proofOfDeliveryAndFiles_areImmutable() {
        UUID shipment = insertShipment("OUT_FOR_DELIVERY", UUID.randomUUID());
        UUID photo = insertFile(shipment, "PHOTO");
        insertProof(shipment, photo, 5);

        assertThatThrownBy(() -> owner.update("UPDATE proof_of_delivery SET recipient_name = 'x'"))
                .hasStackTraceContaining("UPDATE on proof_of_delivery is not allowed");
        assertThatThrownBy(() -> owner.update("DELETE FROM proof_of_delivery"))
                .hasStackTraceContaining("DELETE on proof_of_delivery is not allowed");
        assertThatThrownBy(() -> owner.update("UPDATE pod_files SET sha256 = repeat('b', 64)"))
                .hasStackTraceContaining("UPDATE on pod_files is not allowed");
        assertThatThrownBy(() -> owner.update("DELETE FROM pod_files"))
                .hasStackTraceContaining("DELETE on pod_files is not allowed");
    }

    @Test
    void podFiles_enforceSizeTypeAndKind() {
        UUID shipment = insertShipment("OUT_FOR_DELIVERY", UUID.randomUUID());
        String insert = "INSERT INTO pod_files (id, shipment_id, kind, object_key, sha256, size_bytes, content_type, "
                + "uploaded_by) VALUES (gen_random_uuid(), ?, ?, ?, repeat('a', 64), ?, ?, gen_random_uuid())";

        assertThatThrownBy(() -> owner.update(insert, shipment, "PHOTO", "k1", 5 * 1024 * 1024 + 1, "image/jpeg"))
                .hasStackTraceContaining("pod_files_size_check");
        assertThatThrownBy(() -> owner.update(insert, shipment, "PHOTO", "k2", 10, "image/gif"))
                .hasStackTraceContaining("pod_files_content_type_check");
        assertThatThrownBy(() -> owner.update(insert, shipment, "VIDEO", "k3", 10, "image/png"))
                .hasStackTraceContaining("pod_files_kind_check");
        assertThat(owner.update(insert, shipment, "PHOTO", "k4", 5 * 1024 * 1024, "image/png")).isEqualTo(1);
    }

    @Test
    void deliveryOtp_allowsAtMostFiveAttempts_andAppRoleCannotDeleteIt() {
        UUID shipment = insertShipment("OUT_FOR_DELIVERY", UUID.randomUUID());
        owner.update("INSERT INTO delivery_otps (shipment_id, code_hash, expires_at) "
                + "VALUES (?, 'h', now() + interval '1 day')", shipment);

        assertThat(owner.update("UPDATE delivery_otps SET attempts = 5 WHERE shipment_id = ?", shipment)).isEqualTo(1);
        assertThatThrownBy(() -> owner.update("UPDATE delivery_otps SET attempts = 6 WHERE shipment_id = ?", shipment))
                .hasStackTraceContaining("delivery_otps_attempts_check");
        assertThatThrownBy(() -> app.update("DELETE FROM delivery_otps WHERE shipment_id = ?", shipment))
                .hasStackTraceContaining("permission denied");
        assertThat(app.update("UPDATE delivery_otps SET attempts = 0 WHERE shipment_id = ?", shipment)).isEqualTo(1);
    }

    private UUID insertFile(UUID shipment, String kind) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO pod_files (id, shipment_id, kind, object_key, sha256, size_bytes, content_type, "
                + "uploaded_by) VALUES (?, ?, ?, ?, repeat('a', 64), 10, 'image/jpeg', gen_random_uuid())",
                id, shipment, kind, "pod/" + shipment + "/" + id);
        return id;
    }

    private int insertProof(UUID shipment, UUID photo, double distanceMeters) {
        return owner.update("INSERT INTO proof_of_delivery (shipment_id, courier_id, delivered_latitude, "
                + "delivered_longitude, distance_meters, recipient_name, photo_file_id) "
                + "VALUES (?, gen_random_uuid(), 41.0, 29.0, ?, 'Ada', ?)", shipment, distanceMeters, photo);
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
        assertThatThrownBy(() -> app.update("UPDATE proof_of_delivery SET recipient_name = 'x'"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM proof_of_delivery"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM pod_files"))
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
