package com.routemesh.shipmentservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.routemesh.shipmentservice.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.grpc.server.port=0")
class OutboxRetentionJobTest {

    @Autowired
    private OutboxRetentionJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purge_deletesOnlyEventsOlderThanRetention() {
        String aggregate = "retention-" + UUID.randomUUID();
        insert(aggregate, "8 days");
        insert(aggregate, "7 days 1 hour");
        UUID recent = insert(aggregate, "6 days");
        UUID fresh = insert(aggregate, "0 seconds");

        long deleted = job.purge();

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id FROM outbox_events WHERE aggregate_type = ?", UUID.class, aggregate))
                .containsExactlyInAnyOrder(recent, fresh);
    }

    @Test
    void purge_removesMoreThanOneBatch() {
        String aggregate = "retention-bulk-" + UUID.randomUUID();
        int count = OutboxRetentionJob.BATCH_SIZE + 500;
        jdbc.update("INSERT INTO outbox_events (aggregate_type, aggregate_id, type, payload, created_at) "
                + "SELECT ?, 'x', 'T', '{}'::jsonb, now() - interval '9 days' FROM generate_series(1, ?)",
                aggregate, count);

        long deleted = job.purge();

        assertThat(deleted).isGreaterThanOrEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_type = ?",
                Integer.class, aggregate)).isZero();
    }

    @Test
    void purge_withNothingToDelete_returnsZero() {
        job.purge();

        assertThat(job.purge()).isZero();
    }

    private UUID insert(String aggregate, String age) {
        return jdbc.queryForObject("INSERT INTO outbox_events (aggregate_type, aggregate_id, type, payload, "
                + "created_at) VALUES (?, 'x', 'T', '{}'::jsonb, now() - ?::interval) RETURNING id",
                UUID.class, aggregate, age);
    }
}
