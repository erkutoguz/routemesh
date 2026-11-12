package com.routemesh.searchservice.rest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Listeler yalnızca tam satırları görür: taslak satırlar ({@code merchant_id IS NULL}) her sorguda dışlanır. */
@Repository
class ShipmentSearchRepository {

    record Criteria(UUID merchantId, UUID courierId, List<String> statuses, Instant from, Instant to) {
    }

    record Row(UUID id, UUID merchantId, String status, UUID courierId, long amountMinor, String currency,
               Instant createdAt, Instant updatedAt, Instant deliveredAt) {
    }

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getObject("id", UUID.class),
            rs.getObject("merchant_id", UUID.class), rs.getString("status"), rs.getObject("courier_id", UUID.class),
            rs.getLong("amount_minor"), rs.getString("currency"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toInstant());

    private final JdbcClient jdbc;

    ShipmentSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> page(Criteria criteria, ListCursor after, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, merchant_id, status, courier_id, amount_minor, currency, "
                + "created_at, updated_at, delivered_at FROM shipment_view WHERE merchant_id IS NOT NULL");
        if (criteria.merchantId() != null) {
            sql.append(" AND merchant_id = :merchant");
        }
        if (criteria.courierId() != null) {
            sql.append(" AND courier_id = :courier");
        }
        if (criteria.statuses() != null) {
            sql.append(" AND status IN (:statuses)");
        }
        if (criteria.from() != null) {
            sql.append(" AND created_at >= :from");
        }
        if (criteria.to() != null) {
            sql.append(" AND created_at < :to");
        }
        if (after != null) {
            sql.append(" AND (created_at, id) < (:afterAt, :afterId)");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", limit);
        if (criteria.merchantId() != null) {
            statement = statement.param("merchant", criteria.merchantId());
        }
        if (criteria.courierId() != null) {
            statement = statement.param("courier", criteria.courierId());
        }
        if (criteria.statuses() != null) {
            statement = statement.param("statuses", criteria.statuses());
        }
        if (criteria.from() != null) {
            statement = statement.param("from", Timestamp.from(criteria.from()));
        }
        if (criteria.to() != null) {
            statement = statement.param("to", Timestamp.from(criteria.to()));
        }
        if (after != null) {
            statement = statement.param("afterAt", Timestamp.from(after.createdAt())).param("afterId", after.id());
        }
        return statement.query(ROW).list();
    }
}
