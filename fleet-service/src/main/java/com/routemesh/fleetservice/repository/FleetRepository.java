package com.routemesh.fleetservice.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.routemesh.fleetservice.domain.Courier;
import com.routemesh.fleetservice.domain.CourierStatus;
import com.routemesh.fleetservice.domain.Offer;
import com.routemesh.fleetservice.domain.OfferStatus;

@Repository
public class FleetRepository {

    /** Bir teklifin hangi süre koşuluyla durum değiştirebileceği. */
    public enum Expiry {
        /** Süresi dolmamış olmalı (kabul). */
        NOT_EXPIRED("AND expires_at > now()"),
        /** Süresi dolmuş olmalı (zaman aşımı). */
        EXPIRED("AND expires_at <= now()"),
        ANY("");

        private final String condition;

        Expiry(String condition) {
            this.condition = condition;
        }
    }

    private static final String COURIER_COLUMNS =
            "courier_id, status, max_weight_grams, active_shipment_id, fence_token, updated_at";
    private static final String OFFER_COLUMNS = "offer_id, shipment_id, courier_id, status, weight_grams, "
            + "fence_token, expires_at, created_at, decided_at";

    private final JdbcClient jdbc;

    public FleetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Courier> findCourier(UUID courierId) {
        return jdbc.sql("SELECT " + COURIER_COLUMNS + " FROM couriers WHERE courier_id = :id")
                .param("id", courierId)
                .query((rs, n) -> mapCourier(rs))
                .optional();
    }

    public Optional<Courier> findCourierForUpdate(UUID courierId) {
        return jdbc.sql("SELECT " + COURIER_COLUMNS + " FROM couriers WHERE courier_id = :id FOR UPDATE")
                .param("id", courierId)
                .query((rs, n) -> mapCourier(rs))
                .optional();
    }

    public void ensureCourier(UUID courierId, int maxWeightGrams) {
        jdbc.sql("INSERT INTO couriers (courier_id, max_weight_grams) VALUES (:id, :max) "
                        + "ON CONFLICT (courier_id) DO NOTHING")
                .param("id", courierId)
                .param("max", maxWeightGrams)
                .update();
    }

    public int updateCourierStatus(UUID courierId, CourierStatus from, CourierStatus to) {
        return jdbc.sql("UPDATE couriers SET status = :to, updated_at = now() "
                        + "WHERE courier_id = :id AND status = :from")
                .param("id", courierId)
                .param("from", from.name())
                .param("to", to.name())
                .update();
    }

    /** Teklif verilirken kuryenin fence değerini ilerletir; eski bir token'la yazma reddedilir. */
    public int bumpFence(UUID courierId, long fenceToken) {
        return jdbc.sql("UPDATE couriers SET fence_token = :token, updated_at = now() "
                        + "WHERE courier_id = :id AND status = 'IDLE' AND fence_token < :token")
                .param("id", courierId)
                .param("token", fenceToken)
                .update();
    }

    public int assignCourier(UUID courierId, UUID shipmentId, long fenceToken) {
        return jdbc.sql("UPDATE couriers SET status = 'BUSY', active_shipment_id = :shipment, "
                        + "fence_token = :token, updated_at = now() "
                        + "WHERE courier_id = :id AND status = 'IDLE' AND fence_token < :token")
                .param("id", courierId)
                .param("shipment", shipmentId)
                .param("token", fenceToken)
                .update();
    }

    public int releaseCourier(UUID courierId, UUID shipmentId, long fenceToken) {
        return jdbc.sql("UPDATE couriers SET status = 'IDLE', active_shipment_id = NULL, "
                        + "fence_token = :token, updated_at = now() "
                        + "WHERE courier_id = :id AND active_shipment_id = :shipment AND fence_token < :token")
                .param("id", courierId)
                .param("shipment", shipmentId)
                .param("token", fenceToken)
                .update();
    }

    public Optional<Offer> findOffer(UUID offerId) {
        return jdbc.sql("SELECT " + OFFER_COLUMNS + " FROM assignment_offers WHERE offer_id = :id")
                .param("id", offerId)
                .query((rs, n) -> mapOffer(rs))
                .optional();
    }

    public Offer insertOffer(UUID offerId, UUID shipmentId, UUID courierId, int weightGrams, long fenceToken,
                             int ttlSeconds) {
        return jdbc.sql("INSERT INTO assignment_offers (offer_id, shipment_id, courier_id, status, weight_grams, "
                        + "fence_token, expires_at) VALUES (:id, :shipment, :courier, 'OFFERED', :weight, :token, "
                        + "now() + make_interval(secs => :ttl)) RETURNING " + OFFER_COLUMNS)
                .param("id", offerId)
                .param("shipment", shipmentId)
                .param("courier", courierId)
                .param("weight", weightGrams)
                .param("token", fenceToken)
                .param("ttl", ttlSeconds)
                .query((rs, n) -> mapOffer(rs))
                .single();
    }

    public Optional<Offer> transitionOffer(UUID offerId, OfferStatus from, OfferStatus to, Expiry expiry) {
        return jdbc.sql("UPDATE assignment_offers SET status = :to, decided_at = now() "
                        + "WHERE offer_id = :id AND status = :from " + expiry.condition
                        + " RETURNING " + OFFER_COLUMNS)
                .param("id", offerId)
                .param("from", from.name())
                .param("to", to.name())
                .query((rs, n) -> mapOffer(rs))
                .optional();
    }

    public List<Offer> expireStaleOffers(UUID courierId) {
        return jdbc.sql("UPDATE assignment_offers SET status = 'EXPIRED', decided_at = now() "
                        + "WHERE courier_id = :id AND status = 'OFFERED' AND expires_at <= now() "
                        + "RETURNING " + OFFER_COLUMNS)
                .param("id", courierId)
                .query((rs, n) -> mapOffer(rs))
                .list();
    }

    public Optional<Offer> releaseAcceptedOffer(UUID courierId, UUID shipmentId) {
        return jdbc.sql("UPDATE assignment_offers SET status = 'RELEASED', decided_at = now() "
                        + "WHERE courier_id = :courier AND shipment_id = :shipment AND status = 'ACCEPTED' "
                        + "RETURNING " + OFFER_COLUMNS)
                .param("courier", courierId)
                .param("shipment", shipmentId)
                .query((rs, n) -> mapOffer(rs))
                .optional();
    }

    public List<Offer> findOpenOffers(UUID courierId) {
        return jdbc.sql("SELECT " + OFFER_COLUMNS + " FROM assignment_offers "
                        + "WHERE courier_id = :id AND status = 'OFFERED' AND expires_at > now() "
                        + "ORDER BY created_at")
                .param("id", courierId)
                .query((rs, n) -> mapOffer(rs))
                .list();
    }

    public void insertOutbox(UUID eventId, String aggregateType, String aggregateId, String type, String payload) {
        jdbc.sql("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, type, payload) "
                        + "VALUES (:id, :aggregateType, :aggregateId, :type, CAST(:payload AS jsonb))")
                .param("id", eventId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("type", type)
                .param("payload", payload)
                .update();
    }

    public int deleteOutboxOlderThan(Instant cutoff, int batchSize) {
        return jdbc.sql("DELETE FROM outbox_events WHERE id IN "
                        + "(SELECT id FROM outbox_events WHERE created_at < :cutoff ORDER BY created_at LIMIT :batch)")
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .param("batch", batchSize)
                .update();
    }

    private static Courier mapCourier(ResultSet rs) throws SQLException {
        return new Courier(
                rs.getObject("courier_id", UUID.class),
                CourierStatus.valueOf(rs.getString("status")),
                rs.getInt("max_weight_grams"),
                rs.getObject("active_shipment_id", UUID.class),
                rs.getLong("fence_token"),
                instant(rs, "updated_at"));
    }

    private static Offer mapOffer(ResultSet rs) throws SQLException {
        return new Offer(
                rs.getObject("offer_id", UUID.class),
                rs.getObject("shipment_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                OfferStatus.valueOf(rs.getString("status")),
                rs.getInt("weight_grams"),
                rs.getLong("fence_token"),
                instant(rs, "expires_at"),
                instant(rs, "created_at"),
                instant(rs, "decided_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
