package com.routemesh.notificationservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "routemesh.notification.consumer-enabled=false")
class NotificationSchemaTest {

    @Autowired
    private PostgreSQLContainer postgres;

    private Connection app;

    @BeforeEach
    void connect() throws SQLException {
        app = DriverManager.getConnection(postgres.getJdbcUrl(), "notification_app", "notification_app_test");
    }

    @AfterEach
    void close() throws SQLException {
        app.close();
    }

    @Test
    void sameEventTemplateRecipientAndChannelCanOnlyBeStoredOnce() throws SQLException {
        UUID event = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        insert(event, "NEW_OFFER", recipient, "IN_APP");

        assertThatThrownBy(() -> insert(event, "NEW_OFFER", recipient, "IN_APP"))
                .hasMessageContaining("notifications_dedupe_key");
        insert(event, "NEW_OFFER", recipient, "SMS");
        insert(event, "OTHER", recipient, "IN_APP");
    }

    @Test
    void anSmsNotificationCannotBeMarkedRead() throws SQLException {
        assertThatThrownBy(() -> exec("INSERT INTO notifications (event_id, template, recipient_type, recipient_id, "
                + "channel, read_at) VALUES ('" + UUID.randomUUID() + "', 't', 'RECIPIENT', '" + UUID.randomUUID()
                + "', 'SMS', now())")).hasMessageContaining("notifications_sms_unread_check");
    }

    @Test
    void theApplicationRoleCanOnlyMarkNotificationsReadAndPurgeTheInbox() throws SQLException {
        insert(UUID.randomUUID(), "T", UUID.randomUUID(), "IN_APP");

        assertThat(exec("UPDATE notifications SET read_at = now() WHERE channel = 'IN_APP'")).isPositive();
        assertThatThrownBy(() -> exec("UPDATE notifications SET template = 'X'"))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec("DELETE FROM notifications")).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec("UPDATE processed_events SET topic = 'x'"))
                .hasMessageContaining("permission denied");
        assertThat(exec("DELETE FROM processed_events WHERE false")).isZero();
    }

    private void insert(UUID event, String template, UUID recipient, String channel) throws SQLException {
        String type = "SMS".equals(channel) ? "RECIPIENT" : "COURIER";
        exec("INSERT INTO notifications (event_id, template, recipient_type, recipient_id, channel) VALUES ('"
                + event + "', '" + template + "', '" + type + "', '" + recipient + "', '" + channel + "')");
    }

    private int exec(String sql) throws SQLException {
        try (Statement statement = app.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }
}
