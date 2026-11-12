package com.routemesh.searchservice.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.routemesh.searchservice.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "routemesh.search.consumer-enabled=false")
class InboxCleanupTest {

    @Autowired
    private InboxCleanup cleanup;

    @Autowired
    private InboxRepository inbox;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM processed_events").update();
    }

    @Test
    void registeringTheSameEventTwiceReportsTheDuplicate() {
        UUID id = UUID.randomUUID();

        assertThat(inbox.register(id, "shipment.events")).isTrue();
        assertThat(inbox.register(id, "shipment.events")).isFalse();
    }

    @Test
    void onlyEventsOlderThanTheRetentionArePurged() {
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        inbox.register(old, "t");
        inbox.register(recent, "t");
        jdbc.sql("UPDATE processed_events SET processed_at = now() - interval '31 days' WHERE event_id = :id")
                .param("id", old).update();

        cleanup.purge();

        assertThat(jdbc.sql("SELECT event_id FROM processed_events").query(UUID.class).list())
                .containsExactly(recent);
    }
}
