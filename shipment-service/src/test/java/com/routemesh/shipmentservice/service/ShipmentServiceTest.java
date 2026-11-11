package com.routemesh.shipmentservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.routemesh.shipmentservice.TestcontainersConfiguration;
import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.CreateShipmentCommand;
import com.routemesh.shipmentservice.domain.Shipment;
import com.routemesh.shipmentservice.domain.ShipmentResult;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.grpc.server.port=0")
class ShipmentServiceTest {

    private record Edge(ShipmentStatus from, Actor actor) {
    }

    private static final Map<ShipmentStatus, Edge> PARENT = Map.ofEntries(
            Map.entry(ShipmentStatus.PAID, new Edge(ShipmentStatus.PENDING_PAYMENT, Actor.SYSTEM)),
            Map.entry(ShipmentStatus.AT_WAREHOUSE, new Edge(ShipmentStatus.PAID, Actor.WAREHOUSE)),
            Map.entry(ShipmentStatus.READY_FOR_DISPATCH, new Edge(ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE)),
            Map.entry(ShipmentStatus.ASSIGNED, new Edge(ShipmentStatus.READY_FOR_DISPATCH, Actor.SYSTEM)),
            Map.entry(ShipmentStatus.OUT_FOR_DELIVERY, new Edge(ShipmentStatus.ASSIGNED, Actor.COURIER)),
            Map.entry(ShipmentStatus.DELIVERED, new Edge(ShipmentStatus.OUT_FOR_DELIVERY, Actor.COURIER)),
            Map.entry(ShipmentStatus.DELIVERY_FAILED, new Edge(ShipmentStatus.OUT_FOR_DELIVERY, Actor.COURIER)),
            Map.entry(ShipmentStatus.RETURN_TO_WAREHOUSE, new Edge(ShipmentStatus.DELIVERY_FAILED, Actor.SYSTEM)),
            Map.entry(ShipmentStatus.DAMAGED, new Edge(ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE)),
            Map.entry(ShipmentStatus.ON_HOLD, new Edge(ShipmentStatus.AT_WAREHOUSE, Actor.OPS)),
            Map.entry(ShipmentStatus.CANCELLED, new Edge(ShipmentStatus.PAID, Actor.MERCHANT)),
            Map.entry(ShipmentStatus.LOST, new Edge(ShipmentStatus.AT_WAREHOUSE, Actor.OPS)));

    @Autowired
    private ShipmentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void create_persistsShipmentHistoryAndOutboxEvent() throws Exception {
        CreateShipmentCommand command = newCommand(key());

        ShipmentResult result = service.create(command);
        Shipment shipment = result.shipment();

        assertThat(result.replayed()).isFalse();
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.PENDING_PAYMENT);
        assertThat(shipment.version()).isZero();
        assertThat(shipment.courierId()).isNull();
        assertThat(shipment.merchantId()).isEqualTo(command.merchantId());
        assertThat(shipment.finalAmountMinor()).isEqualTo(1001);
        assertThat(historyCount(shipment.id())).isEqualTo(1);

        JsonNode event = jsonMapper.readTree(outboxPayloads(shipment.id()).get(0));
        assertThat(event.path("eventType").asString()).isEqualTo("ShipmentCreated");
        assertThat(event.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(event.path("aggregateId").asString()).isEqualTo(shipment.id().toString());
        assertThat(event.path("data").path("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(event.path("data").path("finalAmountMinor").asLong()).isEqualTo(1001);
        assertThat(event.path("occurredAt").asString()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT aggregate_type FROM outbox_events WHERE aggregate_id = ?",
                String.class, shipment.id().toString())).isEqualTo("shipment");
    }

    @Test
    void create_sameKeyAndPayload_isReplayedWithoutNewRows() {
        CreateShipmentCommand command = newCommand(key());
        Shipment first = service.create(command).shipment();

        ShipmentResult second = service.create(command);

        assertThat(second.replayed()).isTrue();
        assertThat(second.shipment().id()).isEqualTo(first.id());
        assertThat(count("shipments WHERE idempotency_key = ?", command.idempotencyKey())).isEqualTo(1);
        assertThat(outboxPayloads(first.id())).hasSize(1);
    }

    @Test
    void create_sameKeyWithDifferentPayload_isConflict() {
        String key = key();
        service.create(newCommand(key));
        CreateShipmentCommand different = new CreateShipmentCommand(key, UUID.randomUUID(), "Ada Lovelace",
                "+905551112233", "Somewhere 1", 41.0, 29.0, UUID.randomUUID(), 2002, 1500, "TRY");

        assertReason(() -> service.create(different), Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void create_withClientSuppliedId_usesItAndReplaysIdempotently() {
        UUID id = UUID.randomUUID();
        CreateShipmentCommand command = withId(newCommand(key()), id);

        ShipmentResult first = service.create(command);
        ShipmentResult second = service.create(command);

        assertThat(first.shipment().id()).isEqualTo(id);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.shipment().id()).isEqualTo(id);
    }

    @Test
    void create_sameKeyWithDifferentShipmentId_isConflict() {
        CreateShipmentCommand command = withId(newCommand(key()), UUID.randomUUID());
        service.create(command);

        assertReason(() -> service.create(withId(command, UUID.randomUUID())), Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void create_withShipmentIdAlreadyUsedUnderOtherKey_isConflict() {
        UUID id = UUID.randomUUID();
        service.create(withId(newCommand(key()), id));

        assertReason(() -> service.create(withId(newCommand(key()), id)), Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void create_invalidInputs_areRejected() {
        UUID merchant = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        List<CreateShipmentCommand> invalid = List.of(
                new CreateShipmentCommand(" ", merchant, "Ada", "+905551112233", "Addr", 41, 29, quote, 1001, 800, "TRY"),
                new CreateShipmentCommand(key(), merchant, " ", "+905551112233", "Addr", 41, 29, quote, 1001, 800, "TRY"),
                new CreateShipmentCommand(key(), merchant, "Ada", "12", "Addr", 41, 29, quote, 1001, 800, "TRY"),
                new CreateShipmentCommand(key(), merchant, "Ada", "+905551112233", "", 41, 29, quote, 1001, 800, "TRY"),
                new CreateShipmentCommand(key(), merchant, "Ada", "+905551112233", "Addr", 91, 29, quote, 1001, 800, "TRY"),
                new CreateShipmentCommand(key(), merchant, "Ada", "+905551112233", "Addr", 41, 181, quote, 1001, 800, "TRY"),
                new CreateShipmentCommand(key(), merchant, "Ada", "+905551112233", "Addr", Double.NaN, 29, quote, 1001, 800, "TRY"),
                new CreateShipmentCommand(key(), merchant, "Ada", "+905551112233", "Addr", 41, 29, quote, 0, 800, "TRY"),
                new CreateShipmentCommand(key(), merchant, "Ada", "+905551112233", "Addr", 41, 29, quote, 1001, -1, "TRY"),
                new CreateShipmentCommand(key(), merchant, "Ada", "+905551112233", "Addr", 41, 29, quote, 1001, 800, "USD"),
                new CreateShipmentCommand(key(), merchant, "x".repeat(201), "+905551112233", "Addr", 41, 29, quote, 1001, 800, "TRY"));

        for (CreateShipmentCommand command : invalid) {
            assertReason(() -> service.create(command), Reason.INVALID_REQUEST);
        }
    }

    @Test
    void happyPath_walksEveryStepToDelivered() {
        Shipment shipment = service.create(newCommand(key())).shipment();
        UUID id = shipment.id();
        UUID courier = UUID.randomUUID();

        move(id, ShipmentStatus.PAID, Actor.SYSTEM);
        move(id, ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE);
        move(id, ShipmentStatus.READY_FOR_DISPATCH, Actor.WAREHOUSE);
        service.transition(new TransitionCommand(id, ShipmentStatus.ASSIGNED, Actor.SYSTEM, key(), null, courier, null));
        move(id, ShipmentStatus.OUT_FOR_DELIVERY, Actor.COURIER);
        addProofOfDelivery(id);
        Shipment delivered = move(id, ShipmentStatus.DELIVERED, Actor.COURIER);

        assertThat(delivered.status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(delivered.courierId()).isEqualTo(courier);
        assertThat(delivered.version()).isEqualTo(6);
        assertThat(historyCount(id)).isEqualTo(7);
        assertThat(outboxPayloads(id)).hasSize(7);
    }

    @Test
    void statusChangedEvent_carriesTransitionDetails() throws Exception {
        UUID id = shipmentIn(ShipmentStatus.PAID);

        service.transition(new TransitionCommand(id, ShipmentStatus.CANCELLED, Actor.MERCHANT, key(),
                "changed my mind", null, ShipmentStatus.PAID));

        List<String> payloads = outboxPayloads(id);
        JsonNode event = jsonMapper.readTree(payloads.get(payloads.size() - 1));
        assertThat(event.path("eventType").asString()).isEqualTo("ShipmentStatusChanged");
        assertThat(event.path("data").path("fromStatus").asString()).isEqualTo("PAID");
        assertThat(event.path("data").path("toStatus").asString()).isEqualTo("CANCELLED");
        assertThat(event.path("data").path("actor").asString()).isEqualTo("MERCHANT");
        assertThat(event.path("data").path("reason").asString()).isEqualTo("changed my mind");
        assertThat(event.path("data").path("version").asLong()).isEqualTo(2);
    }

    @Test
    void delivered_requiresProofOfDelivery() {
        UUID id = shipmentIn(ShipmentStatus.OUT_FOR_DELIVERY);

        assertReason(() -> move(id, ShipmentStatus.DELIVERED, Actor.COURIER), Reason.ILLEGAL_TRANSITION);
        assertThat(service.get(id).status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);

        addProofOfDelivery(id);
        assertThat(move(id, ShipmentStatus.DELIVERED, Actor.COURIER).status()).isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    void everyStatusAndActorCombination_followsTheTransitionTable() {
        Set<String> allowed = new HashSet<>(jdbc.queryForList(
                "SELECT from_status || '>' || to_status || '>' || actor FROM shipment_transitions", String.class));

        for (ShipmentStatus from : ShipmentStatus.values()) {
            UUID id = shipmentIn(from);
            for (ShipmentStatus to : ShipmentStatus.values()) {
                for (Actor actor : Actor.values()) {
                    if (allowed.contains(from + ">" + to + ">" + actor)) {
                        continue;
                    }
                    UUID courier = to == ShipmentStatus.ASSIGNED ? UUID.randomUUID() : null;
                    assertReason(() -> service.transition(
                            new TransitionCommand(id, to, actor, key(), null, courier, null)),
                            Reason.ILLEGAL_TRANSITION);
                }
            }
            assertThat(service.get(id).status()).isEqualTo(from);
        }

        for (String entry : allowed) {
            String[] parts = entry.split(">");
            ShipmentStatus from = ShipmentStatus.valueOf(parts[0]);
            ShipmentStatus to = ShipmentStatus.valueOf(parts[1]);
            Actor actor = Actor.valueOf(parts[2]);
            UUID id = shipmentIn(from);
            if (to == ShipmentStatus.DELIVERED) {
                addProofOfDelivery(id);
            }
            UUID courier = to == ShipmentStatus.ASSIGNED ? UUID.randomUUID() : null;

            Shipment moved = service.transition(new TransitionCommand(id, to, actor, key(), null, courier, null))
                    .shipment();

            assertThat(moved.status()).as(entry).isEqualTo(to);
        }
    }

    @Test
    void transition_sameKeyAndPayload_isReplayed() {
        UUID id = shipmentIn(ShipmentStatus.PAID);
        String key = key();
        TransitionCommand command = new TransitionCommand(id, ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE,
                key, null, null, null);

        ShipmentResult first = service.transition(command);
        ShipmentResult second = service.transition(command);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.shipment().status()).isEqualTo(ShipmentStatus.AT_WAREHOUSE);
        assertThat(second.shipment().version()).isEqualTo(first.shipment().version());
    }

    @Test
    void transition_sameKeyWithDifferentPayload_isConflict() {
        UUID id = shipmentIn(ShipmentStatus.PAID);
        String key = key();
        service.transition(new TransitionCommand(id, ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE, key, null,
                null, null));

        assertReason(() -> service.transition(new TransitionCommand(id, ShipmentStatus.CANCELLED, Actor.MERCHANT,
                key, null, null, null)), Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void transition_sameKeyOnDifferentShipments_isIndependent() {
        UUID first = shipmentIn(ShipmentStatus.PAID);
        UUID second = shipmentIn(ShipmentStatus.PAID);
        String key = key();

        service.transition(new TransitionCommand(first, ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE, key, null,
                null, null));
        ShipmentResult result = service.transition(new TransitionCommand(second, ShipmentStatus.AT_WAREHOUSE,
                Actor.WAREHOUSE, key, null, null, null));

        assertThat(result.replayed()).isFalse();
    }

    @Test
    void transition_expectedFromStatusMismatch_isRejected() {
        UUID id = shipmentIn(ShipmentStatus.PAID);

        assertReason(() -> service.transition(new TransitionCommand(id, ShipmentStatus.AT_WAREHOUSE,
                Actor.WAREHOUSE, key(), null, null, ShipmentStatus.READY_FOR_DISPATCH)), Reason.ILLEGAL_TRANSITION);
    }

    @Test
    void transition_unknownShipment_isNotFound() {
        assertReason(() -> service.transition(new TransitionCommand(UUID.randomUUID(), ShipmentStatus.PAID,
                Actor.SYSTEM, key(), null, null, null)), Reason.NOT_FOUND);
        assertReason(() -> service.get(UUID.randomUUID()), Reason.NOT_FOUND);
    }

    @Test
    void transition_invalidInputs_areRejected() {
        UUID id = shipmentIn(ShipmentStatus.PENDING_PAYMENT);

        assertReason(() -> service.transition(new TransitionCommand(id, ShipmentStatus.PAID, Actor.SYSTEM, " ",
                null, null, null)), Reason.INVALID_REQUEST);
        assertReason(() -> service.transition(new TransitionCommand(id, ShipmentStatus.PAID, Actor.SYSTEM, key(),
                "x".repeat(501), null, null)), Reason.INVALID_REQUEST);
    }

    @Test
    void courier_isRequiredWhenAssigningAndNotAllowedOtherwise() {
        UUID ready = shipmentIn(ShipmentStatus.READY_FOR_DISPATCH);
        UUID paid = shipmentIn(ShipmentStatus.PAID);

        assertReason(() -> service.transition(new TransitionCommand(ready, ShipmentStatus.ASSIGNED, Actor.SYSTEM,
                key(), null, null, null)), Reason.INVALID_REQUEST);
        assertReason(() -> service.transition(new TransitionCommand(paid, ShipmentStatus.AT_WAREHOUSE,
                Actor.WAREHOUSE, key(), null, UUID.randomUUID(), null)), Reason.INVALID_REQUEST);
    }

    @Test
    void courier_isReleasedWhenAssignmentIsUndoneOrCancelled_andKeptOtherwise() {
        UUID released = shipmentIn(ShipmentStatus.ASSIGNED);
        assertThat(service.get(released).courierId()).isNotNull();
        assertThat(move(released, ShipmentStatus.READY_FOR_DISPATCH, Actor.SYSTEM).courierId()).isNull();

        UUID cancelled = shipmentIn(ShipmentStatus.ASSIGNED);
        assertThat(move(cancelled, ShipmentStatus.CANCELLED, Actor.MERCHANT).courierId()).isNull();

        UUID failed = shipmentIn(ShipmentStatus.OUT_FOR_DELIVERY);
        UUID courier = service.get(failed).courierId();
        assertThat(courier).isNotNull();
        assertThat(move(failed, ShipmentStatus.DELIVERY_FAILED, Actor.COURIER).courierId()).isEqualTo(courier);
        assertThat(move(failed, ShipmentStatus.RETURN_TO_WAREHOUSE, Actor.SYSTEM).courierId()).isEqualTo(courier);
    }

    @Test
    void concurrentConflictingTransitions_exactlyOneWins() throws Exception {
        UUID id = shipmentIn(ShipmentStatus.PAID);
        long historyBefore = historyCount(id);

        List<Callable<Boolean>> tasks = List.of(
                () -> tryMove(id, ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE),
                () -> tryMove(id, ShipmentStatus.CANCELLED, Actor.MERCHANT),
                () -> tryMove(id, ShipmentStatus.CANCELLED, Actor.OPS));

        long winners = runConcurrently(tasks).stream().filter(Boolean::booleanValue).count();

        assertThat(winners).isEqualTo(1);
        assertThat(historyCount(id)).isEqualTo(historyBefore + 1);
        assertThat(service.get(id).version()).isEqualTo(2);
    }

    @Test
    void concurrentSameKeyTransitions_createExactlyOneHistoryEntry() throws Exception {
        UUID id = shipmentIn(ShipmentStatus.PAID);
        TransitionCommand command = new TransitionCommand(id, ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE,
                key(), null, null, null);
        List<Callable<ShipmentResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> service.transition(command));
        }

        List<ShipmentResult> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(count("shipment_status_history WHERE shipment_id = ? AND to_status = 'AT_WAREHOUSE'", id))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameKeyCreates_createExactlyOneShipment() throws Exception {
        CreateShipmentCommand command = newCommand(key());
        List<Callable<ShipmentResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> service.create(command));
        }

        List<ShipmentResult> results = runConcurrently(tasks);

        assertThat(results.stream().map(r -> r.shipment().id()).distinct().count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(count("shipments WHERE idempotency_key = ?", command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void weightIsStoredWhenGivenAndRejectedWhenNotPositive() {
        assertThat(service.create(withWeight(newCommand(key()), 2500L)).shipment().weightGrams()).isEqualTo(2500L);
        assertThat(service.create(newCommand(key())).shipment().weightGrams()).isNull();

        assertReason(() -> service.create(withWeight(newCommand(key()), 0L)), Reason.INVALID_REQUEST);
        assertReason(() -> service.create(withWeight(newCommand(key()), Integer.MAX_VALUE + 1L)),
                Reason.INVALID_REQUEST);
    }

    @Test
    void systemCanPutReadyShipmentOnHold() {
        UUID id = shipmentIn(ShipmentStatus.READY_FOR_DISPATCH);

        assertThat(move(id, ShipmentStatus.ON_HOLD, Actor.SYSTEM).status()).isEqualTo(ShipmentStatus.ON_HOLD);
    }

    private static CreateShipmentCommand withWeight(CreateShipmentCommand c, Long weightGrams) {
        return new CreateShipmentCommand(c.idempotencyKey(), c.merchantId(), c.recipientName(), c.recipientPhone(),
                c.deliveryAddress(), c.deliveryLatitude(), c.deliveryLongitude(), c.quoteId(),
                c.finalAmountMinor(), c.basePriceMinor(), c.currency(), null, weightGrams);
    }

    private UUID shipmentIn(ShipmentStatus target) {
        if (target == ShipmentStatus.PENDING_PAYMENT) {
            return service.create(newCommand(key())).shipment().id();
        }
        Edge edge = PARENT.get(target);
        UUID id = shipmentIn(edge.from());
        if (target == ShipmentStatus.DELIVERED) {
            addProofOfDelivery(id);
        }
        UUID courier = target == ShipmentStatus.ASSIGNED ? UUID.randomUUID() : null;
        service.transition(new TransitionCommand(id, target, edge.actor(), key(), null, courier, null));
        return id;
    }

    private Shipment move(UUID id, ShipmentStatus to, Actor actor) {
        return service.transition(new TransitionCommand(id, to, actor, key(), null, null, null)).shipment();
    }

    private boolean tryMove(UUID id, ShipmentStatus to, Actor actor) {
        try {
            move(id, to, actor);
            return true;
        } catch (ShipmentException e) {
            assertThat(e.getReason()).isEqualTo(Reason.ILLEGAL_TRANSITION);
            return false;
        }
    }

    private void addProofOfDelivery(UUID id) {
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM proof_of_delivery WHERE shipment_id = ?)", Boolean.class, id))) {
            return;
        }
        UUID fileId = UUID.randomUUID();
        jdbc.update("INSERT INTO pod_files (id, shipment_id, kind, object_key, sha256, size_bytes, content_type, "
                + "uploaded_by) VALUES (?, ?, 'PHOTO', ?, repeat('a', 64), 10, 'image/jpeg', gen_random_uuid())",
                fileId, id, "pod/" + id + "/" + fileId + ".jpg");
        jdbc.update("INSERT INTO proof_of_delivery (shipment_id, courier_id, delivered_latitude, delivered_longitude, "
                + "distance_meters, recipient_name, photo_file_id) VALUES (?, gen_random_uuid(), 40.9876, 29.0369, "
                + "5, 'Ada Lovelace', ?)", id, fileId);
    }

    private static CreateShipmentCommand withId(CreateShipmentCommand c, UUID id) {
        return new CreateShipmentCommand(c.idempotencyKey(), c.merchantId(), c.recipientName(), c.recipientPhone(),
                c.deliveryAddress(), c.deliveryLatitude(), c.deliveryLongitude(), c.quoteId(),
                c.finalAmountMinor(), c.basePriceMinor(), c.currency(), id);
    }

    private static CreateShipmentCommand newCommand(String key) {
        return new CreateShipmentCommand(key, UUID.randomUUID(), "Ada Lovelace", "+905551112233",
                "Moda Cd. 12, Kadikoy", 40.9876, 29.0369, UUID.randomUUID(), 1001, 800, "TRY");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private long historyCount(UUID shipmentId) {
        return count("shipment_status_history WHERE shipment_id = ?", shipmentId);
    }

    private List<String> outboxPayloads(UUID shipmentId) {
        return jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE aggregate_id = ? "
                + "ORDER BY created_at, id", String.class, shipmentId.toString());
    }

    private int count(String fromAndWhere, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromAndWhere, Integer.class, args);
    }

    private static void assertReason(Runnable action, Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ShipmentException.class,
                        e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
