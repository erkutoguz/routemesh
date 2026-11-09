package com.routemesh.shipmentservice.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.CreateShipmentCommand;
import com.routemesh.shipmentservice.domain.HistoryEntry;
import com.routemesh.shipmentservice.domain.Shipment;
import com.routemesh.shipmentservice.domain.ShipmentStatus;

@Repository
public class ShipmentRepository {

    public record StoredShipment(Shipment shipment, String requestHash) {
    }

    private static final String COLUMNS = "id, merchant_id, status, courier_id, version, recipient_name, "
            + "recipient_phone, delivery_address, delivery_latitude, delivery_longitude, quote_id, "
            + "final_amount_minor, base_price_minor, currency, created_at, updated_at, request_hash";

    private final JdbcClient jdbc;

    public ShipmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredShipment> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM shipments WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> mapStored(rs))
                .optional();
    }

    public Optional<Shipment> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM shipments WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> mapStored(rs).shipment())
                .optional();
    }

    public Optional<Shipment> findByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM shipments WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, rowNum) -> mapStored(rs).shipment())
                .optional();
    }

    public Optional<Shipment> insert(CreateShipmentCommand command, String requestHash) {
        return jdbc.sql("INSERT INTO shipments (idempotency_key, request_hash, merchant_id, status, "
                        + "recipient_name, recipient_phone, delivery_address, delivery_latitude, "
                        + "delivery_longitude, quote_id, final_amount_minor, base_price_minor, currency) "
                        + "VALUES (:key, :hash, :merchant, 'PENDING_PAYMENT', :name, :phone, :address, :lat, :lon, "
                        + ":quote, :finalAmount, :basePrice, :currency) "
                        + "ON CONFLICT (idempotency_key) DO NOTHING RETURNING " + COLUMNS)
                .param("key", command.idempotencyKey())
                .param("hash", requestHash)
                .param("merchant", command.merchantId())
                .param("name", command.recipientName())
                .param("phone", command.recipientPhone())
                .param("address", command.deliveryAddress())
                .param("lat", command.deliveryLatitude())
                .param("lon", command.deliveryLongitude())
                .param("quote", command.quoteId())
                .param("finalAmount", command.finalAmountMinor())
                .param("basePrice", command.basePriceMinor())
                .param("currency", command.currency())
                .query((rs, rowNum) -> mapStored(rs).shipment())
                .optional();
    }

    public Optional<Boolean> findTransitionRequiresPod(ShipmentStatus from, ShipmentStatus to, Actor actor) {
        return jdbc.sql("SELECT requires_pod FROM shipment_transitions "
                        + "WHERE from_status = :from AND to_status = :to AND actor = :actor")
                .param("from", from.name())
                .param("to", to.name())
                .param("actor", actor.name())
                .query(Boolean.class)
                .optional();
    }

    public boolean transitionExists(ShipmentStatus from, ShipmentStatus to) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM shipment_transitions "
                        + "WHERE from_status = :from AND to_status = :to)")
                .param("from", from.name())
                .param("to", to.name())
                .query(Boolean.class)
                .single();
    }

    public boolean proofOfDeliveryExists(UUID shipmentId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM proof_of_delivery WHERE shipment_id = :id)")
                .param("id", shipmentId)
                .query(Boolean.class)
                .single();
    }

    public Optional<String> findHistoryRequestHash(UUID shipmentId, String transitionKey) {
        return jdbc.sql("SELECT request_hash FROM shipment_status_history "
                        + "WHERE shipment_id = :id AND transition_key = :key")
                .param("id", shipmentId)
                .param("key", transitionKey)
                .query(String.class)
                .optional();
    }

    public int updateStatus(UUID id, ShipmentStatus from, ShipmentStatus to, UUID courierId) {
        return jdbc.sql("UPDATE shipments SET status = :to, courier_id = :courier, version = version + 1, "
                        + "updated_at = now() WHERE id = :id AND status = :from")
                .param("id", id)
                .param("from", from.name())
                .param("to", to.name())
                .param("courier", courierId, Types.OTHER)
                .update();
    }

    public List<HistoryEntry> findHistory(UUID shipmentId) {
        return jdbc.sql("SELECT from_status, to_status, actor, courier_id, reason, version, created_at "
                        + "FROM shipment_status_history WHERE shipment_id = :shipment ORDER BY id")
                .param("shipment", shipmentId)
                .query((rs, n) -> new HistoryEntry(
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("actor"),
                        rs.getObject("courier_id", UUID.class),
                        rs.getString("reason"),
                        rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public void insertHistory(UUID shipmentId, String transitionKey, String requestHash, ShipmentStatus from,
                              ShipmentStatus to, Actor actor, UUID courierId, String reason, long version) {
        jdbc.sql("INSERT INTO shipment_status_history (shipment_id, transition_key, request_hash, from_status, "
                        + "to_status, actor, courier_id, reason, version) "
                        + "VALUES (:shipment, :key, :hash, :from, :to, :actor, :courier, :reason, :version)")
                .param("shipment", shipmentId)
                .param("key", transitionKey)
                .param("hash", requestHash)
                .param("from", from == null ? null : from.name(), Types.VARCHAR)
                .param("to", to.name())
                .param("actor", actor.name())
                .param("courier", courierId, Types.OTHER)
                .param("reason", reason, Types.VARCHAR)
                .param("version", version)
                .update();
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

    private static StoredShipment mapStored(ResultSet rs) throws SQLException {
        Shipment shipment = new Shipment(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                ShipmentStatus.valueOf(rs.getString("status")),
                rs.getObject("courier_id", UUID.class),
                rs.getLong("version"),
                rs.getString("recipient_name"),
                rs.getString("recipient_phone"),
                rs.getString("delivery_address"),
                rs.getDouble("delivery_latitude"),
                rs.getDouble("delivery_longitude"),
                rs.getObject("quote_id", UUID.class),
                rs.getLong("final_amount_minor"),
                rs.getLong("base_price_minor"),
                rs.getString("currency"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
        return new StoredShipment(shipment, rs.getString("request_hash"));
    }
}
