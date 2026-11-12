package com.routemesh.notificationservice.rest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.routemesh.notificationservice.handler.RecipientType;

@Repository
class NotificationInboxRepository {

    record Row(UUID id, String template, UUID shipmentId, String params, Instant createdAt, Instant readAt) {
    }

    private static final String COLUMNS = "id, template, shipment_id, params::text AS params, created_at, read_at";

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getObject("id", UUID.class),
            rs.getString("template"), rs.getObject("shipment_id", UUID.class), rs.getString("params"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant());

    private final JdbcClient jdbc;

    NotificationInboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> page(RecipientType type, UUID recipient, boolean unreadOnly, InboxCursor after, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM notifications "
                + "WHERE recipient_id = :recipient AND recipient_type = :type AND channel = 'IN_APP'");
        if (unreadOnly) {
            sql.append(" AND read_at IS NULL");
        }
        if (after != null) {
            sql.append(" AND (created_at, id) < (:createdAt, :id)");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString())
                .param("recipient", recipient)
                .param("type", type.name())
                .param("limit", limit);
        if (after != null) {
            statement = statement.param("createdAt", Timestamp.from(after.createdAt())).param("id", after.id());
        }
        return statement.query(ROW).list();
    }

    long unreadCount(RecipientType type, UUID recipient) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE recipient_id = :recipient "
                        + "AND recipient_type = :type AND channel = 'IN_APP' AND read_at IS NULL")
                .param("recipient", recipient)
                .param("type", type.name())
                .query(Long.class).single();
    }

    Optional<Row> markRead(RecipientType type, UUID recipient, UUID id) {
        return jdbc.sql("UPDATE notifications SET read_at = COALESCE(read_at, now()) WHERE id = :id "
                        + "AND recipient_id = :recipient AND recipient_type = :type AND channel = 'IN_APP' "
                        + "RETURNING " + COLUMNS)
                .param("id", id)
                .param("recipient", recipient)
                .param("type", type.name())
                .query(ROW).optional();
    }
}
