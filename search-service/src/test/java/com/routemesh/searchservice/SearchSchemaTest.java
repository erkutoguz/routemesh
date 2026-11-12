package com.routemesh.searchservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "routemesh.search.consumer-enabled=false")
class SearchSchemaTest {

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcClient jdbc;

    private Connection app;

    @BeforeEach
    void connect() throws SQLException {
        app = DriverManager.getConnection(postgres.getJdbcUrl(), "search_app", "search_app_test");
        jdbc.sql("DELETE FROM shipment_view").update();
    }

    @AfterEach
    void close() throws SQLException {
        app.close();
    }

    @Test
    void theCompositeIndexesForTheListQueriesExist() {
        List<String> indexes = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'shipment_view'")
                .query(String.class).list();

        assertThat(indexes).anySatisfy(def -> assertThat(def)
                .contains("(merchant_id, created_at DESC, id DESC)"));
        assertThat(indexes).anySatisfy(def -> assertThat(def).contains("(status, created_at DESC, id DESC)"));
        assertThat(indexes).anySatisfy(def -> assertThat(def).contains("(courier_id, created_at DESC, id DESC)"));
    }

    @Test
    void aSkeletonRowMustBeEitherCompleteOrCompletelyEmpty() {
        assertThatThrownBy(() -> exec("INSERT INTO shipment_view (id, merchant_id, status, version, created_at, "
                + "updated_at) VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID()
                + "', 'PAID', 1, now(), now())")).hasMessageContaining("shipment_view_skeleton_check");
        assertThatThrownBy(() -> exec("INSERT INTO shipment_view (id, status, version, amount_minor, currency, "
                + "created_at, updated_at) VALUES ('" + UUID.randomUUID() + "', 'PAID', 1, 100, 'TRY', now(), now())"))
                .hasMessageContaining("shipment_view_skeleton_check");
        assertThatThrownBy(() -> exec("INSERT INTO shipment_view (id, status, version, created_at, updated_at) "
                + "VALUES ('" + UUID.randomUUID() + "', 'PAID', -1, now(), now())"))
                .hasMessageContaining("version");
    }

    @Test
    void theApplicationRoleCanProjectButNeverDeleteViewRowsAndCanPurgeTheInbox() throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO shipment_view (id, status, version, created_at, updated_at) VALUES ('" + id
                + "', 'PAID', 1, now(), now())");
        assertThat(exec("UPDATE shipment_view SET status = 'ASSIGNED', version = 2 WHERE id = '" + id + "'"))
                .isEqualTo(1);

        assertThatThrownBy(() -> exec("DELETE FROM shipment_view")).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec("UPDATE processed_events SET topic = 'x'"))
                .hasMessageContaining("permission denied");
        assertThat(exec("DELETE FROM processed_events WHERE false")).isZero();
    }

    private int exec(String sql) throws SQLException {
        try (Statement statement = app.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }
}
