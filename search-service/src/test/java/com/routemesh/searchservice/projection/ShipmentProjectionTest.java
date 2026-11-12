package com.routemesh.searchservice.projection;

import static com.routemesh.searchservice.Events.MERCHANT;
import static com.routemesh.searchservice.Events.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.routemesh.searchservice.Events;
import com.routemesh.searchservice.TestcontainersConfiguration;

import tools.jackson.core.JacksonException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "routemesh.search.consumer-enabled=false")
class ShipmentProjectionTest {

    private static final String TOPIC = "shipment.events";

    @Autowired
    private EventDispatcher dispatcher;

    @Autowired
    private JdbcClient jdbc;

    private final UUID shipment = UUID.randomUUID();
    private final UUID courier = UUID.randomUUID();

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM shipment_view").update();
        jdbc.sql("DELETE FROM processed_events").update();
    }

    @Test
    void aCreatedEventOpensTheViewRow() {
        dispatch(Events.created(UUID.randomUUID(), shipment, T0));

        Map<String, Object> row = row(shipment);
        assertThat(row).containsEntry("merchant_id", MERCHANT).containsEntry("status", "PENDING_PAYMENT")
                .containsEntry("version", 0L).containsEntry("amount_minor", 7500L).containsEntry("currency", "TRY");
        assertThat(row.get("courier_id")).isNull();
        assertThat(instant(row, "created_at")).isEqualTo(T0);
        assertThat(row.get("delivered_at")).isNull();
    }

    @Test
    void aNewerStatusEventUpdatesStatusCourierAndTime() {
        dispatch(Events.created(UUID.randomUUID(), shipment, T0));

        dispatch(Events.statusChanged(UUID.randomUUID(), shipment, "ASSIGNED", courier, 5, T0.plusSeconds(60)));

        Map<String, Object> row = row(shipment);
        assertThat(row).containsEntry("status", "ASSIGNED").containsEntry("version", 5L)
                .containsEntry("courier_id", courier);
        assertThat(instant(row, "updated_at")).isEqualTo(T0.plusSeconds(60));
        assertThat(instant(row, "created_at")).isEqualTo(T0);
    }

    @Test
    void aStatusEventBeforeTheCreatedEventOpensASkeletonThatCreatedLaterCompletesWithoutOverwritingTheState() {
        dispatch(Events.statusChanged(UUID.randomUUID(), shipment, "ASSIGNED", courier, 5, T0.plusSeconds(60)));

        Map<String, Object> skeleton = row(shipment);
        assertThat(skeleton.get("merchant_id")).isNull();
        assertThat(skeleton.get("amount_minor")).isNull();
        assertThat(skeleton).containsEntry("status", "ASSIGNED").containsEntry("version", 5L);

        dispatch(Events.created(UUID.randomUUID(), shipment, T0));

        Map<String, Object> row = row(shipment);
        assertThat(row).containsEntry("merchant_id", MERCHANT).containsEntry("amount_minor", 7500L)
                .containsEntry("currency", "TRY").containsEntry("status", "ASSIGNED").containsEntry("version", 5L)
                .containsEntry("courier_id", courier);
        assertThat(instant(row, "created_at")).isEqualTo(T0);
        assertThat(instant(row, "updated_at")).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void anOlderOrEqualVersionIsRejected() {
        dispatch(Events.created(UUID.randomUUID(), shipment, T0));
        dispatch(Events.statusChanged(UUID.randomUUID(), shipment, "OUT_FOR_DELIVERY", courier, 6, T0.plusSeconds(60)));

        dispatch(Events.statusChanged(UUID.randomUUID(), shipment, "ASSIGNED", courier, 5, T0.plusSeconds(30)));
        dispatch(Events.statusChanged(UUID.randomUUID(), shipment, "ON_HOLD", null, 6, T0.plusSeconds(90)));

        assertThat(row(shipment)).containsEntry("status", "OUT_FOR_DELIVERY").containsEntry("version", 6L)
                .containsEntry("courier_id", courier);
        assertThat(instant(row(shipment), "updated_at")).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void aLaterVersionCanClearTheCourier() {
        dispatch(Events.created(UUID.randomUUID(), shipment, T0));
        dispatch(Events.statusChanged(UUID.randomUUID(), shipment, "ASSIGNED", courier, 1, T0.plusSeconds(1)));

        dispatch(Events.statusChanged(UUID.randomUUID(), shipment, "READY_FOR_DISPATCH", null, 2, T0.plusSeconds(2)));

        assertThat(row(shipment).get("courier_id")).isNull();
    }

    @Test
    void everyArrivalOrderOfTheSameEventsEndsInTheSameState() {
        List<String> events = new ArrayList<>();
        UUID id = UUID.randomUUID();
        for (String event : List.of(
                Events.created(UUID.randomUUID(), id, T0),
                Events.statusChanged(UUID.randomUUID(), id, "ASSIGNED", courier, 1, T0.plusSeconds(1)),
                Events.statusChanged(UUID.randomUUID(), id, "OUT_FOR_DELIVERY", courier, 2, T0.plusSeconds(2)),
                Events.statusChanged(UUID.randomUUID(), id, "DELIVERED", courier, 3, T0.plusSeconds(3)))) {
            events.add(event);
        }
        List<List<String>> orders = new ArrayList<>();
        permute(events, 0, orders);
        assertThat(orders).hasSize(24);

        for (List<String> order : orders) {
            jdbc.sql("DELETE FROM shipment_view").update();
            jdbc.sql("DELETE FROM processed_events").update();
            order.forEach(this::dispatch);

            Map<String, Object> row = row(id);
            assertThat(row).containsEntry("merchant_id", MERCHANT).containsEntry("amount_minor", 7500L)
                    .containsEntry("status", "DELIVERED").containsEntry("version", 3L)
                    .containsEntry("courier_id", courier);
            assertThat(instant(row, "created_at")).isEqualTo(T0);
            assertThat(instant(row, "updated_at")).isEqualTo(T0.plusSeconds(3));
            assertThat(instant(row, "delivered_at")).isEqualTo(T0.plusSeconds(3));
        }
    }

    @Test
    void deliveredAtSurvivesWhenTheDeliveredEventArrivesAfterALaterVersion() {
        dispatch(Events.created(UUID.randomUUID(), shipment, T0));
        dispatch(Events.statusChanged(UUID.randomUUID(), shipment, "RETURN_TO_WAREHOUSE", null, 5, T0.plusSeconds(50)));

        dispatch(Events.statusChanged(UUID.randomUUID(), shipment, "DELIVERED", courier, 4, T0.plusSeconds(40)));

        Map<String, Object> row = row(shipment);
        assertThat(row).containsEntry("status", "RETURN_TO_WAREHOUSE").containsEntry("version", 5L);
        assertThat(instant(row, "delivered_at")).isEqualTo(T0.plusSeconds(40));
    }

    @Test
    void aRedeliveredEventIsAppliedOnce() {
        UUID eventId = UUID.randomUUID();
        dispatch(Events.created(UUID.randomUUID(), shipment, T0));
        dispatch(Events.statusChanged(eventId, shipment, "ASSIGNED", courier, 1, T0.plusSeconds(1)));

        dispatch(Events.statusChanged(eventId, shipment, "OUT_FOR_DELIVERY", courier, 2, T0.plusSeconds(2)));

        assertThat(row(shipment)).containsEntry("status", "ASSIGNED").containsEntry("version", 1L);
        assertThat(count("processed_events")).isEqualTo(2);
    }

    @Test
    void otherEventTypesLeaveNoTrace() {
        dispatch(Events.envelope(UUID.randomUUID(), "ShipmentRefunded", T0, "{\"shipmentId\":\"" + shipment + "\"}"));

        assertThat(count("processed_events")).isZero();
        assertThat(count("shipment_view")).isZero();
    }

    @Test
    void invalidMessagesFailAndLeaveNothingBehind() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> dispatch("{not json")).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatch(Events.statusChanged(id, shipment, "X", null, 1, T0).replace(
                shipment.toString(), "nope"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatch(Events.statusChanged(id, shipment, "X", null, 1, T0).replace(
                T0.toString(), "yesterday"))).isInstanceOf(DateTimeException.class);
        assertThatThrownBy(() -> dispatch(Events.statusChanged(id, shipment, "X", null, 0, T0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatch(Events.statusChanged(id, shipment, "", null, 1, T0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatch(Events.created(id, shipment, T0).replace("7500", "0")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("processed_events")).isZero();
        assertThat(count("shipment_view")).isZero();
    }

    private void dispatch(String message) {
        dispatcher.dispatch(TOPIC, message);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.sql("SELECT * FROM shipment_view WHERE id = :id").param("id", id).query().singleRow();
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static void permute(List<String> items, int from, List<List<String>> out) {
        if (from == items.size()) {
            out.add(new ArrayList<>(items));
            return;
        }
        for (int i = from; i < items.size(); i++) {
            java.util.Collections.swap(items, from, i);
            permute(items, from + 1, out);
            java.util.Collections.swap(items, from, i);
        }
    }
}
