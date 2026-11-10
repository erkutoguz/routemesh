package com.routemesh.fleetservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Kuralların uygulama kodundan bağımsız olarak veritabanında da kilitli olduğunu doğrular. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.grpc.server.port=0")
class FleetSchemaTest {

    @Autowired
    private PostgreSQLContainer postgres;

    private JdbcTemplate app;

    @BeforeEach
    void setUp() {
        app = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), "fleet_app", "fleet_app_test"));
    }

    @Test
    void busyRequiresAnActiveShipmentAndViceVersa() {
        UUID busyWithoutShipment = courier("IDLE");
        UUID idleWithShipment = courier("IDLE");

        assertThatThrownBy(() -> app.update(
                "UPDATE couriers SET status = 'BUSY' WHERE courier_id = ?", busyWithoutShipment))
                .hasMessageContaining("couriers_busy_check");
        assertThatThrownBy(() -> app.update(
                "UPDATE couriers SET active_shipment_id = ? WHERE courier_id = ?", UUID.randomUUID(),
                idleWithShipment))
                .hasMessageContaining("couriers_busy_check");
    }

    @Test
    void shipmentCanBelongToOnlyOneCourier() {
        UUID shipment = UUID.randomUUID();
        UUID first = courier("IDLE");
        UUID second = courier("IDLE");
        assign(first, shipment, 1);

        assertThatThrownBy(() -> assign(second, shipment, 1)).hasMessageContaining("couriers_active_shipment_idx");
    }

    @Test
    void busyCourierCannotJumpToAnotherShipmentOrGoOffline() {
        UUID courier = courier("IDLE");
        assign(courier, UUID.randomUUID(), 1);

        assertThatThrownBy(() -> app.update(
                "UPDATE couriers SET active_shipment_id = ? WHERE courier_id = ?", UUID.randomUUID(), courier))
                .hasMessageContaining("must be released");
        assertThatThrownBy(() -> app.update(
                "UPDATE couriers SET status = 'OFFLINE', active_shipment_id = NULL WHERE courier_id = ?", courier))
                .hasMessageContaining("cannot go offline while busy");
    }

    @Test
    void fenceTokenCanNeverDecrease() {
        UUID courier = courier("IDLE");
        app.update("UPDATE couriers SET fence_token = 5 WHERE courier_id = ?", courier);

        assertThatThrownBy(() -> app.update("UPDATE couriers SET fence_token = 4 WHERE courier_id = ?", courier))
                .hasMessageContaining("cannot decrease");
    }

    @Test
    void courierHasAtMostOneOpenOfferAndShipmentAtMostOneLiveOffer() {
        UUID first = courier("IDLE");
        UUID second = courier("IDLE");
        UUID shipment = UUID.randomUUID();
        offer(first, shipment, "OFFERED");

        assertThatThrownBy(() -> offer(first, UUID.randomUUID(), "OFFERED"))
                .hasMessageContaining("assignment_offers_open_courier_idx");
        assertThatThrownBy(() -> offer(second, shipment, "OFFERED"))
                .hasMessageContaining("assignment_offers_live_shipment_idx");
    }

    @Test
    void closedOffersDoNotBlockNewOnes() {
        UUID courier = courier("IDLE");
        UUID shipment = UUID.randomUUID();
        offer(courier, shipment, "REJECTED");

        offer(courier, shipment, "OFFERED");

        assertThat(app.queryForObject("SELECT count(*) FROM assignment_offers WHERE shipment_id = ?",
                Integer.class, shipment)).isEqualTo(2);
    }

    @Test
    void offerCannotBeDeletedByTheApplicationRole() {
        assertThatThrownBy(() -> app.update("DELETE FROM assignment_offers"))
                .rootCause().hasMessageContaining("permission denied");
    }

    private UUID courier(String status) {
        UUID id = UUID.randomUUID();
        app.update("INSERT INTO couriers (courier_id, status, max_weight_grams) VALUES (?, ?, 20000)", id, status);
        return id;
    }

    private void assign(UUID courier, UUID shipment, long token) {
        app.update("UPDATE couriers SET status = 'BUSY', active_shipment_id = ?, fence_token = ? "
                + "WHERE courier_id = ?", shipment, token, courier);
    }

    private void offer(UUID courier, UUID shipment, String status) {
        app.update("INSERT INTO assignment_offers (offer_id, shipment_id, courier_id, status, weight_grams, "
                        + "fence_token, expires_at, decided_at) VALUES (?, ?, ?, ?, 1000, 1, now() + interval '1 minute', "
                        + "CASE WHEN ? = 'OFFERED' THEN NULL ELSE now() END)",
                UUID.randomUUID(), shipment, courier, status, status);
    }
}
