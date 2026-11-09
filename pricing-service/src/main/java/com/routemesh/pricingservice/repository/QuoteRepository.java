package com.routemesh.pricingservice.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.routemesh.pricingservice.domain.CreateQuoteCommand;
import com.routemesh.pricingservice.domain.Quote;
import com.routemesh.pricingservice.domain.QuoteStatus;
import com.routemesh.pricingservice.domain.RateCard;
import com.routemesh.pricingservice.domain.ServiceLevel;
import com.routemesh.pricingservice.domain.ServiceLevelConfig;

@Repository
public class QuoteRepository {

    public record StoredQuote(Quote quote, String requestHash) {
    }

    private static final String SELECT_QUOTE = "SELECT q.id, q.merchant_id, q.origin_latitude, q.origin_longitude, "
            + "q.destination_latitude, q.destination_longitude, z.code AS zone_code, q.service_level, "
            + "q.weight_grams, q.distance_meters, q.base_price_minor, q.surge_bps, q.final_amount_minor, "
            + "q.currency, q.sla_minutes, "
            + "CASE WHEN q.redeemed_at IS NOT NULL THEN 'REDEEMED' WHEN q.expires_at <= now() THEN 'EXPIRED' "
            + "ELSE 'ACTIVE' END AS status, "
            + "q.created_at, q.expires_at, q.redeemed_shipment_id, q.redemption_key, q.request_hash "
            + "FROM quotes q JOIN pricing_zones z ON z.id = q.zone_id ";

    private static final String POINT = "ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography";

    private final JdbcClient jdbc;

    public QuoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RateCard> findRateCard(double latitude, double longitude) {
        return jdbc.sql("SELECT z.code, r.base_fee_minor, r.per_km_minor, r.included_weight_grams, "
                        + "r.per_extra_kg_minor, r.min_fee_minor FROM pricing_zones z "
                        + "JOIN zone_rates r ON r.zone_id = z.id "
                        + "WHERE z.active AND ST_Covers(z.geom, " + POINT.formatted(":lon", ":lat") + ") "
                        + "ORDER BY z.priority DESC, ST_Area(z.geom) ASC LIMIT 1")
                .param("lat", latitude)
                .param("lon", longitude)
                .query((rs, rowNum) -> new RateCard(
                        rs.getString("code"),
                        rs.getLong("base_fee_minor"),
                        rs.getLong("per_km_minor"),
                        rs.getInt("included_weight_grams"),
                        rs.getLong("per_extra_kg_minor"),
                        rs.getLong("min_fee_minor")))
                .optional();
    }

    public Optional<ServiceLevelConfig> findServiceLevel(ServiceLevel level) {
        return jdbc.sql("SELECT code, multiplier_bps, sla_minutes FROM service_levels WHERE code = :code")
                .param("code", level.name())
                .query((rs, rowNum) -> new ServiceLevelConfig(
                        ServiceLevel.valueOf(rs.getString("code")),
                        rs.getInt("multiplier_bps"),
                        rs.getInt("sla_minutes")))
                .optional();
    }

    public long distanceMeters(double originLat, double originLon, double destLat, double destLon) {
        return jdbc.sql("SELECT floor(ST_Distance(" + POINT.formatted(":oLon", ":oLat") + ", "
                        + POINT.formatted(":dLon", ":dLat") + "))::bigint")
                .param("oLat", originLat)
                .param("oLon", originLon)
                .param("dLat", destLat)
                .param("dLon", destLon)
                .query(Long.class)
                .single();
    }

    public Optional<StoredQuote> findByIdempotencyKey(String key) {
        return jdbc.sql(SELECT_QUOTE + "WHERE q.idempotency_key = :key")
                .param("key", key)
                .query((rs, rowNum) -> mapStored(rs))
                .optional();
    }

    public Optional<Quote> findById(UUID id) {
        return jdbc.sql(SELECT_QUOTE + "WHERE q.id = :id")
                .param("id", id)
                .query((rs, rowNum) -> mapStored(rs).quote())
                .optional();
    }

    public Optional<Quote> findByIdForUpdate(UUID id) {
        return jdbc.sql(SELECT_QUOTE + "WHERE q.id = :id FOR UPDATE OF q")
                .param("id", id)
                .query((rs, rowNum) -> mapStored(rs).quote())
                .optional();
    }

    public Optional<UUID> insert(CreateQuoteCommand command, String requestHash, RateCard rate, long distanceMeters,
                                 long basePriceMinor, int surgeBps, long finalAmountMinor, int slaMinutes,
                                 long ttlSeconds) {
        return jdbc.sql("INSERT INTO quotes (idempotency_key, request_hash, merchant_id, origin_latitude, "
                        + "origin_longitude, destination_latitude, destination_longitude, zone_id, service_level, "
                        + "weight_grams, distance_meters, base_price_minor, surge_bps, final_amount_minor, "
                        + "currency, sla_minutes, expires_at) "
                        + "VALUES (:key, :hash, :merchant, :oLat, :oLon, :dLat, :dLon, "
                        + "(SELECT id FROM pricing_zones WHERE code = :zone), :level, :weight, :distance, :base, "
                        + ":surge, :finalAmount, 'TRY', :sla, now() + (:ttl * interval '1 second')) "
                        + "ON CONFLICT (idempotency_key) DO NOTHING RETURNING id")
                .param("key", command.idempotencyKey())
                .param("hash", requestHash)
                .param("merchant", command.merchantId())
                .param("oLat", command.originLatitude())
                .param("oLon", command.originLongitude())
                .param("dLat", command.destinationLatitude())
                .param("dLon", command.destinationLongitude())
                .param("zone", rate.zoneCode())
                .param("level", command.serviceLevel().name())
                .param("weight", command.weightGrams())
                .param("distance", distanceMeters)
                .param("base", basePriceMinor)
                .param("surge", surgeBps)
                .param("finalAmount", finalAmountMinor)
                .param("sla", slaMinutes)
                .param("ttl", ttlSeconds)
                .query(UUID.class)
                .optional();
    }

    public int redeem(UUID id, String redemptionKey, UUID shipmentId) {
        return jdbc.sql("UPDATE quotes SET redeemed_at = now(), redemption_key = :key, "
                        + "redeemed_shipment_id = :shipment WHERE id = :id AND redeemed_at IS NULL "
                        + "AND expires_at > now()")
                .param("id", id)
                .param("key", redemptionKey)
                .param("shipment", shipmentId)
                .update();
    }

    private static StoredQuote mapStored(ResultSet rs) throws SQLException {
        Quote quote = new Quote(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getDouble("origin_latitude"),
                rs.getDouble("origin_longitude"),
                rs.getDouble("destination_latitude"),
                rs.getDouble("destination_longitude"),
                rs.getString("zone_code"),
                ServiceLevel.valueOf(rs.getString("service_level")),
                rs.getInt("weight_grams"),
                rs.getLong("distance_meters"),
                rs.getLong("base_price_minor"),
                rs.getInt("surge_bps"),
                rs.getLong("final_amount_minor"),
                rs.getString("currency"),
                rs.getInt("sla_minutes"),
                QuoteStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getObject("redeemed_shipment_id", UUID.class),
                rs.getString("redemption_key"));
        return new StoredQuote(quote, rs.getString("request_hash"));
    }
}
