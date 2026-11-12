package com.routemesh.searchservice.inbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InboxRepository {

    private final JdbcClient jdbc;

    public InboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean register(UUID eventId, String topic) {
        return jdbc.sql("INSERT INTO processed_events (event_id, topic) VALUES (:id, :topic) "
                        + "ON CONFLICT (event_id) DO NOTHING")
                .param("id", eventId)
                .param("topic", topic)
                .update() == 1;
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM processed_events WHERE processed_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }
}
