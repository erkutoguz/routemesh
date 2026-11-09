package com.routemesh.pricingservice;

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

import com.routemesh.pricingservice.repository.QuoteRepository;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.grpc.server.port=0")
class PricingSchemaTest {

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private QuoteRepository repository;

    private JdbcTemplate owner;
    private JdbcTemplate app;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        app = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), "pricing_app", "pricing_app_test"));
    }

    @Test
    void referenceDataIsSeeded() {
        assertThat(count("pricing_zones")).isEqualTo(3);
        assertThat(count("zone_rates")).isEqualTo(3);
        assertThat(owner.queryForList("SELECT code || ':' || multiplier_bps || ':' || sla_minutes "
                + "FROM service_levels ORDER BY code", String.class))
                .containsExactly("EXPRESS:15000:120", "STANDARD:10000:240");
    }

    @Test
    void zoneGeometryHasASpatialIndex() {
        assertThat(owner.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'pricing_zones_geom_idx'",
                String.class)).containsIgnoringCase("USING gist");
    }

    @Test
    void pointInsideACoreZone_matchesItBeforeTheMetroZone() {
        assertThat(zoneAt(41.04, 28.95)).isEqualTo("IST_EUROPE_CORE");
        assertThat(zoneAt(40.99, 29.06)).isEqualTo("IST_ASIA_CORE");
    }

    @Test
    void pointOnlyInsideTheMetroZone_matchesIt() {
        assertThat(zoneAt(41.20, 29.40)).isEqualTo("IST_METRO");
    }

    @Test
    void pointOutsideEveryZone_matchesNothing() {
        assertThat(repository.findRateCard(39.93, 32.85)).isEmpty();
        assertThat(repository.findRateCard(41.0, 27.0)).isEmpty();
    }

    @Test
    void pointOnTheBoundaryBetweenTwoEqualPriorityZones_prefersTheSmallerZone() {
        assertThat(zoneAt(41.02, 29.00)).isEqualTo("IST_EUROPE_CORE");
    }

    @Test
    void inactiveZone_isIgnored() {
        UUID id = owner.queryForObject("INSERT INTO pricing_zones (code, name, geom, priority, active) VALUES "
                + "('TEST_INACTIVE', 'x', ST_GeogFromText('SRID=4326;MULTIPOLYGON(((30 40, 30.1 40, 30.1 40.1, "
                + "30 40.1, 30 40)))'), 50, false) RETURNING id", UUID.class);
        owner.update("INSERT INTO zone_rates VALUES (?, 1, 1, 1000, 1, 1)", id);

        assertThat(repository.findRateCard(40.05, 30.05)).isEmpty();
    }

    @Test
    void higherPriorityZone_winsOverALowerOne() {
        UUID id = owner.queryForObject("INSERT INTO pricing_zones (code, name, geom, priority) VALUES "
                + "('TEST_PRIORITY', 'x', ST_GeogFromText('SRID=4326;MULTIPOLYGON(((31 40, 31.1 40, 31.1 40.1, "
                + "31 40.1, 31 40)))'), 100) RETURNING id", UUID.class);
        owner.update("INSERT INTO zone_rates VALUES (?, 1, 1, 1000, 1, 1)", id);
        owner.update("INSERT INTO pricing_zones (code, name, geom, priority) VALUES "
                + "('TEST_PRIORITY_LOW', 'x', ST_GeogFromText('SRID=4326;MULTIPOLYGON(((30.9 39.9, 31.2 39.9, "
                + "31.2 40.2, 30.9 40.2, 30.9 39.9)))'), 1)");

        assertThat(zoneAt(40.05, 31.05)).isEqualTo("TEST_PRIORITY");
    }

    @Test
    void invalidGeometry_isRejected() {
        assertThatThrownBy(() -> owner.update("INSERT INTO pricing_zones (code, name, geom) VALUES "
                + "('TEST_BOWTIE', 'x', ST_GeogFromText('SRID=4326;MULTIPOLYGON(((0 0, 1 1, 1 0, 0 1, 0 0)))'))"))
                .hasStackTraceContaining("pricing_zones_geom_valid_check");
    }

    @Test
    void appRole_canReadReferenceDataButNotChangeIt() {
        assertThat(app.queryForObject("SELECT count(*) FROM pricing_zones", Integer.class)).isEqualTo(3);

        assertThatThrownBy(() -> app.update("UPDATE zone_rates SET base_fee_minor = 1"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM pricing_zones"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("UPDATE service_levels SET multiplier_bps = 1"))
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void quotes_cannotBeDeletedOrTruncated() {
        UUID id = insertQuote("now() + interval '10 minutes'", "now()");

        assertThatThrownBy(() -> owner.update("DELETE FROM quotes WHERE id = ?", id))
                .hasStackTraceContaining("DELETE on quotes is not allowed");
        assertThatThrownBy(() -> owner.execute("TRUNCATE quotes"))
                .hasStackTraceContaining("TRUNCATE on quotes is not allowed");
        assertThatThrownBy(() -> app.update("DELETE FROM quotes WHERE id = ?", id))
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void appRole_canOnlyChangeRedemptionColumns() {
        UUID id = insertQuote("now() + interval '10 minutes'", "now()");

        assertThatThrownBy(() -> app.update("UPDATE quotes SET final_amount_minor = 1 WHERE id = ?", id))
                .hasStackTraceContaining("permission denied");
        app.update("UPDATE quotes SET redeemed_at = now(), redemption_key = 'k1', redeemed_shipment_id = ? "
                + "WHERE id = ?", UUID.randomUUID(), id);
        assertThat(owner.queryForObject("SELECT redemption_key FROM quotes WHERE id = ?", String.class, id))
                .isEqualTo("k1");
    }

    @Test
    void ownerCannotRewriteQuoteFieldsOrARedeemedQuote() {
        UUID id = insertQuote("now() + interval '10 minutes'", "now()");

        assertThatThrownBy(() -> owner.update("UPDATE quotes SET final_amount_minor = 1 WHERE id = ?", id))
                .hasStackTraceContaining("only redemption columns");
        assertThatThrownBy(() -> owner.update("UPDATE quotes SET expires_at = now() WHERE id = ?", id))
                .hasStackTraceContaining("only redemption columns");

        owner.update("UPDATE quotes SET redeemed_at = now(), redemption_key = ?, redeemed_shipment_id = ? "
                + "WHERE id = ?", UUID.randomUUID().toString(), UUID.randomUUID(), id);
        assertThatThrownBy(() -> owner.update("UPDATE quotes SET redemption_key = 'other' WHERE id = ?", id))
                .hasStackTraceContaining("already been redeemed");
    }

    @Test
    void redemptionColumns_mustBeSetTogetherAndBeUnique() {
        UUID first = insertQuote("now() + interval '10 minutes'", "now()");
        UUID second = insertQuote("now() + interval '10 minutes'", "now()");
        UUID shipment = UUID.randomUUID();

        assertThatThrownBy(() -> owner.update("UPDATE quotes SET redeemed_at = now() WHERE id = ?", first))
                .hasStackTraceContaining("quotes_redemption_check");

        owner.update("UPDATE quotes SET redeemed_at = now(), redemption_key = 'dup', redeemed_shipment_id = ? "
                + "WHERE id = ?", shipment, first);
        assertThatThrownBy(() -> owner.update("UPDATE quotes SET redeemed_at = now(), redemption_key = 'dup', "
                + "redeemed_shipment_id = ? WHERE id = ?", UUID.randomUUID(), second))
                .hasStackTraceContaining("duplicate key");
        assertThatThrownBy(() -> owner.update("UPDATE quotes SET redeemed_at = now(), redemption_key = 'other', "
                + "redeemed_shipment_id = ? WHERE id = ?", shipment, second))
                .hasStackTraceContaining("duplicate key");
    }

    @Test
    void invalidQuoteValues_areRejected() {
        assertThatThrownBy(() -> insertQuote("now() + interval '10 minutes'", "now()", "surge_bps", "9999"))
                .hasStackTraceContaining("quotes_surge_check");
        assertThatThrownBy(() -> insertQuote("now() + interval '10 minutes'", "now()", "currency", "'USD'"))
                .hasStackTraceContaining("quotes_currency_check");
        assertThatThrownBy(() -> insertQuote("now() + interval '10 minutes'", "now()", "weight_grams", "0"))
                .hasStackTraceContaining("quotes_weight_check");
        assertThatThrownBy(() -> insertQuote("now() + interval '10 minutes'", "now()", "weight_grams", "30001"))
                .hasStackTraceContaining("quotes_weight_check");
        assertThatThrownBy(() -> insertQuote("now() + interval '10 minutes'", "now()", "final_amount_minor", "0"))
                .hasStackTraceContaining("quotes_amounts_check");
        assertThatThrownBy(() -> insertQuote("now() - interval '1 minute'", "now()"))
                .hasStackTraceContaining("quotes_expiry_check");
    }

    private String zoneAt(double latitude, double longitude) {
        return repository.findRateCard(latitude, longitude).orElseThrow().zoneCode();
    }

    private UUID insertQuote(String expiresAt, String createdAt, String... override) {
        String column = override.length == 2 ? override[0] : "";
        String value = override.length == 2 ? override[1] : "";
        return owner.queryForObject(
                "INSERT INTO quotes (idempotency_key, request_hash, merchant_id, origin_latitude, origin_longitude, "
                        + "destination_latitude, destination_longitude, zone_id, service_level, weight_grams, "
                        + "distance_meters, base_price_minor, surge_bps, final_amount_minor, currency, sla_minutes, "
                        + "created_at, expires_at) SELECT '" + UUID.randomUUID() + "', 'h', gen_random_uuid(), "
                        + "41.0, 28.9, 41.0, 29.0, z.id, 'STANDARD', "
                        + v(column, "weight_grams", value, "1000") + ", 1000, 5500, "
                        + v(column, "surge_bps", value, "10000") + ", "
                        + v(column, "final_amount_minor", value, "5500") + ", "
                        + v(column, "currency", value, "'TRY'") + ", 240, " + createdAt + ", " + expiresAt
                        + " FROM pricing_zones z WHERE z.code = 'IST_ASIA_CORE' RETURNING id",
                UUID.class);
    }

    private static String v(String column, String name, String overrideValue, String defaultValue) {
        return column.equals(name) ? overrideValue : defaultValue;
    }

    private int count(String table) {
        return owner.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
