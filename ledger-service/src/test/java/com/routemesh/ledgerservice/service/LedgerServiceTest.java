package com.routemesh.ledgerservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
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

import com.routemesh.ledgerservice.TestcontainersConfiguration;
import com.routemesh.ledgerservice.domain.Direction;
import com.routemesh.ledgerservice.domain.EntryCommand;
import com.routemesh.ledgerservice.domain.PostTransactionCommand;
import com.routemesh.ledgerservice.domain.PostedTransaction;
import com.routemesh.ledgerservice.service.LedgerException.Reason;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.grpc.server.port=0")
class LedgerServiceTest {

    private static final String PSP = "ASSET:PSP_CLEARING";
    private static final String ESCROW = "LIABILITY:ESCROW";
    private static final String PLATFORM_FEE = "REVENUE:PLATFORM_FEE";

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MeterRegistry meters;

    @Test
    void post_balancedTransaction_updatesBalances() {
        UUID shipment = UUID.randomUUID();

        PostedTransaction posted = capture(shipment, 1001);

        assertThat(posted.replayed()).isFalse();
        assertThat(posted.id()).isNotNull();
        assertThat(ledger.balance(ESCROW, shipment)).isEqualTo(1001);
        assertThat(ledger.balance(PSP, shipment)).isEqualTo(1001);
    }

    @Test
    void postedTransactionsAreCountedByTypeAndReplaysAreNot() {
        UUID shipment = UUID.randomUUID();
        double before = posted("TEST_TRANSACTION");
        PostTransactionCommand command = command(key(), shipment, debit(PSP, 500), credit(ESCROW, 500));

        ledger.post(command);
        ledger.post(command);
        assertReason(() -> ledger.post(command(key(), shipment, debit(PSP, 500), credit(ESCROW, 400))),
                Reason.INVALID_REQUEST);

        assertThat(posted("TEST_TRANSACTION") - before).isEqualTo(1);
    }

    private double posted(String type) {
        var counter = meters.find("routemesh.ledger.transactions.posted").tag("type", type).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void settlement_splitsEscrowIntoWalletsAndPlatformFee() {
        UUID shipment = UUID.randomUUID();
        String merchant = wallet("MERCHANT_WALLET");
        String courier = wallet("COURIER_WALLET");
        capture(shipment, 1001);

        ledger.post(command(key(), shipment,
                debit(ESCROW, 1001), credit(merchant, 750), credit(courier, 180), credit(PLATFORM_FEE, 71)));

        assertThat(ledger.balance(ESCROW, shipment)).isZero();
        assertThat(ledger.balance(merchant, null)).isEqualTo(750);
        assertThat(ledger.balance(courier, null)).isEqualTo(180);
        assertThat(ledger.balance(PLATFORM_FEE, shipment)).isEqualTo(71);
    }

    @Test
    void post_sameKeyAndSamePayload_isReplayedWithoutNewRows() {
        UUID shipment = UUID.randomUUID();
        String key = key();
        PostTransactionCommand command = command(key, shipment, debit(PSP, 500), credit(ESCROW, 500));

        PostedTransaction first = ledger.post(command);
        PostedTransaction second = ledger.post(command);

        assertThat(second.replayed()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(ledger.balance(ESCROW, shipment)).isEqualTo(500);
        assertThat(count("journal_transactions WHERE idempotency_key = ?", key)).isEqualTo(1);
    }

    @Test
    void post_replayIgnoresEntryOrder() {
        UUID shipment = UUID.randomUUID();
        String key = key();
        PostedTransaction first = ledger.post(command(key, shipment, debit(PSP, 500), credit(ESCROW, 500)));

        PostedTransaction second = ledger.post(command(key, shipment, credit(ESCROW, 500), debit(PSP, 500)));

        assertThat(second.replayed()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void post_sameKeyWithDifferentPayload_isConflict() {
        UUID shipment = UUID.randomUUID();
        String key = key();
        ledger.post(command(key, shipment, debit(PSP, 500), credit(ESCROW, 500)));

        assertReason(() -> ledger.post(command(key, shipment, debit(PSP, 600), credit(ESCROW, 600))),
                Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void post_unbalancedTransaction_isRejected() {
        assertReason(() -> ledger.post(command(key(), UUID.randomUUID(), debit(PSP, 500), credit(ESCROW, 400))),
                Reason.INVALID_REQUEST);
    }

    @Test
    void post_invalidInputs_areRejected() {
        UUID shipment = UUID.randomUUID();
        List<PostTransactionCommand> invalid = List.of(
                command(key(), shipment, debit(PSP, 500)),
                command(key(), shipment, debit(PSP, 0), credit(ESCROW, 0)),
                command(key(), shipment, debit(PSP, -5), credit(ESCROW, -5)),
                command(" ", shipment, debit(PSP, 5), credit(ESCROW, 5)),
                command(key(), shipment, new EntryCommand(PSP, Direction.DEBIT, 5, "USD"), credit(ESCROW, 5)),
                command(key(), shipment, new EntryCommand(PSP, null, 5, "TRY"), credit(ESCROW, 5)),
                command(key(), shipment, debit("not-an-account", 5), credit(ESCROW, 5)),
                command(key(), shipment, debit(PSP, Long.MAX_VALUE), debit(PSP, 1), credit(ESCROW, 5)),
                new PostTransactionCommand(key(), "lower_case", shipment, null,
                        List.of(debit(PSP, 5), credit(ESCROW, 5))));

        for (PostTransactionCommand command : invalid) {
            assertReason(() -> ledger.post(command), Reason.INVALID_REQUEST);
        }
    }

    @Test
    void post_escrowWithoutShipment_isRejected() {
        assertReason(() -> ledger.post(command(key(), null, debit(PSP, 500), credit(ESCROW, 500))),
                Reason.INVALID_REQUEST);
    }

    @Test
    void post_unknownSystemAccount_isNotFound() {
        assertReason(() -> ledger.post(command(key(), UUID.randomUUID(),
                        debit(PSP, 5), credit("LIABILITY:UNKNOWN_ACCOUNT", 5))),
                Reason.ACCOUNT_NOT_FOUND);
    }

    @Test
    void post_walletIsCreatedOnFirstUse() {
        UUID shipment = UUID.randomUUID();
        String merchant = wallet("MERCHANT_WALLET");
        capture(shipment, 100);

        ledger.post(command(key(), shipment, debit(ESCROW, 100), credit(merchant, 100)));

        assertThat(count("accounts WHERE code = ?", merchant)).isEqualTo(1);
    }

    @Test
    void post_cannotSpendMoreThanShipmentEscrowHolds() {
        UUID shipment = UUID.randomUUID();
        UUID otherShipment = UUID.randomUUID();
        capture(shipment, 1000);
        capture(otherShipment, 5000);

        assertReason(() -> ledger.post(command(key(), shipment, debit(ESCROW, 1001), credit(PLATFORM_FEE, 1001))),
                Reason.INSUFFICIENT_FUNDS);
        ledger.post(command(key(), shipment, debit(ESCROW, 1000), credit(PLATFORM_FEE, 1000)));

        assertThat(ledger.balance(ESCROW, shipment)).isZero();
        assertThat(ledger.balance(ESCROW, otherShipment)).isEqualTo(5000);
    }

    @Test
    void post_cannotSpendMoreThanWalletHolds() {
        UUID shipment = UUID.randomUUID();
        String merchant = wallet("MERCHANT_WALLET");
        capture(shipment, 300);
        ledger.post(command(key(), shipment, debit(ESCROW, 300), credit(merchant, 300)));

        assertReason(() -> ledger.post(command(key(), null, debit(merchant, 301), credit(PSP, 301))),
                Reason.INSUFFICIENT_FUNDS);
        ledger.post(command(key(), null, debit(merchant, 300), credit(PSP, 300)));

        assertThat(ledger.balance(merchant, null)).isZero();
    }

    @Test
    void reversal_mirrorsOriginalAndRestoresBalances() {
        UUID shipment = UUID.randomUUID();
        PostedTransaction original = capture(shipment, 700);

        ledger.post(reversal(original.id(), shipment, debit(ESCROW, 700), credit(PSP, 700)));

        assertThat(ledger.balance(ESCROW, shipment)).isZero();
        assertThat(ledger.balance(PSP, shipment)).isZero();
    }

    @Test
    void reversal_thatDoesNotMirrorOriginal_isRejected() {
        UUID shipment = UUID.randomUUID();
        PostedTransaction original = capture(shipment, 700);

        assertReason(() -> ledger.post(reversal(original.id(), shipment, debit(ESCROW, 600), credit(PSP, 600))),
                Reason.INVALID_REVERSAL);
        assertReason(() -> ledger.post(reversal(original.id(), UUID.randomUUID(),
                debit(ESCROW, 700), credit(PSP, 700))), Reason.INVALID_REVERSAL);
    }

    @Test
    void reversal_canHappenOnlyOnce() {
        UUID shipment = UUID.randomUUID();
        PostedTransaction original = capture(shipment, 700);
        ledger.post(reversal(original.id(), shipment, debit(ESCROW, 700), credit(PSP, 700)));

        assertReason(() -> ledger.post(reversal(original.id(), shipment, debit(ESCROW, 700), credit(PSP, 700))),
                Reason.INVALID_REVERSAL);
    }

    @Test
    void reversal_ofUnknownTransaction_isRejected() {
        assertReason(() -> ledger.post(reversal(UUID.randomUUID(), UUID.randomUUID(),
                debit(ESCROW, 1), credit(PSP, 1))), Reason.INVALID_REVERSAL);
    }

    @Test
    void balance_unknownAccount_isNotFound() {
        assertReason(() -> ledger.balance(wallet("COURIER_WALLET"), null), Reason.ACCOUNT_NOT_FOUND);
    }

    @Test
    void balance_invalidAccountCode_isRejected() {
        assertReason(() -> ledger.balance("nope", null), Reason.INVALID_REQUEST);
    }

    @Test
    void concurrentSpending_neverOverdrawsEscrow() throws Exception {
        UUID shipment = UUID.randomUUID();
        capture(shipment, 1000);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                try {
                    ledger.post(command(key(), shipment, debit(ESCROW, 100), credit(PLATFORM_FEE, 100)));
                    return true;
                } catch (LedgerException e) {
                    assertThat(e.getReason()).isEqualTo(Reason.INSUFFICIENT_FUNDS);
                    return false;
                }
            });
        }

        long succeeded = runConcurrently(tasks).stream().filter(Boolean::booleanValue).count();

        assertThat(succeeded).isEqualTo(10);
        assertThat(ledger.balance(ESCROW, shipment)).isZero();
    }

    @Test
    void concurrentSameKey_createsExactlyOneTransaction() throws Exception {
        String key = key();
        PostTransactionCommand command = command(key, null, debit(PSP, 100), credit(PLATFORM_FEE, 100));

        List<Callable<PostedTransaction>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> ledger.post(command));
        }

        List<PostedTransaction> results = runConcurrently(tasks);

        assertThat(results).extracting(PostedTransaction::id).containsOnly(results.get(0).id());
        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(count("journal_transactions WHERE idempotency_key = ?", key)).isEqualTo(1);
    }

    @Test
    void post_writesOneEventWithAllEntries_keyedByShipment() throws Exception {
        UUID shipment = UUID.randomUUID();
        String merchant = wallet("MERCHANT_WALLET");
        capture(shipment, 1001);

        PostedTransaction settlement = ledger.post(command(key(), shipment,
                debit(ESCROW, 1001), credit(merchant, 750), credit(PLATFORM_FEE, 251)));

        List<JsonNode> events = eventsFor(shipment.toString());
        assertThat(events).hasSize(2);
        JsonNode event = events.get(1);
        assertThat(event.path("eventType").asString()).isEqualTo("LedgerTransactionPosted");
        assertThat(event.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(event.path("aggregateId").asString()).isEqualTo(shipment.toString());
        assertThat(event.path("occurredAt").asString()).isNotBlank();
        JsonNode data = event.path("data");
        assertThat(data.path("transactionId").asString()).isEqualTo(settlement.id().toString());
        assertThat(data.path("transactionType").asString()).isEqualTo("TEST_TRANSACTION");
        assertThat(data.path("shipmentId").asString()).isEqualTo(shipment.toString());
        assertThat(data.path("currency").asString()).isEqualTo("TRY");
        assertThat(data.path("entries")).hasSize(3);
        assertThat(data.path("entries").get(0).path("accountCode").asString()).isEqualTo(ESCROW);
        assertThat(data.path("entries").get(0).path("direction").asString()).isEqualTo("DEBIT");
        assertThat(data.path("entries").get(0).path("amountMinor").asLong()).isEqualTo(1001);
        assertThat(data.path("entries").get(1).path("accountCode").asString()).isEqualTo(merchant);
        assertThat(data.path("entries").get(1).path("direction").asString()).isEqualTo("CREDIT");
    }

    @Test
    void post_withoutShipment_usesTransactionIdAsEventKey() throws Exception {
        PostedTransaction posted = ledger.post(command(key(), null, debit(PSP, 100), credit(PLATFORM_FEE, 100)));

        List<JsonNode> events = eventsFor(posted.id().toString());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("data").path("shipmentId").isNull()).isTrue();
        assertThat(events.get(0).path("data").path("transactionId").asString()).isEqualTo(posted.id().toString());
    }

    @Test
    void post_replayedOrRejected_doesNotWriteAnotherEvent() throws Exception {
        UUID shipment = UUID.randomUUID();
        PostTransactionCommand command = command(key(), shipment, debit(PSP, 500), credit(ESCROW, 500));
        ledger.post(command);
        ledger.post(command);

        assertReason(() -> ledger.post(command(key(), shipment, debit(ESCROW, 501), credit(PLATFORM_FEE, 501))),
                Reason.INSUFFICIENT_FUNDS);
        assertReason(() -> ledger.post(command(key(), shipment, debit(PSP, 5), credit(ESCROW, 4))),
                Reason.INVALID_REQUEST);

        assertThat(eventsFor(shipment.toString())).hasSize(1);
    }

    @Test
    void reversal_eventCarriesReversalOf() throws Exception {
        UUID shipment = UUID.randomUUID();
        PostedTransaction original = capture(shipment, 700);

        ledger.post(reversal(original.id(), shipment, debit(ESCROW, 700), credit(PSP, 700)));

        List<JsonNode> events = eventsFor(shipment.toString());
        assertThat(events).hasSize(2);
        assertThat(events.get(1).path("data").path("reversalOf").asString()).isEqualTo(original.id().toString());
        assertThat(events.get(0).path("data").path("reversalOf").isNull()).isTrue();
    }

    @Test
    void concurrentPosts_writeOneEventEach() throws Exception {
        UUID shipment = UUID.randomUUID();
        capture(shipment, 1000);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                try {
                    ledger.post(command(key(), shipment, debit(ESCROW, 100), credit(PLATFORM_FEE, 100)));
                    return true;
                } catch (LedgerException e) {
                    return false;
                }
            });
        }

        long succeeded = runConcurrently(tasks).stream().filter(Boolean::booleanValue).count();

        assertThat(eventsFor(shipment.toString())).hasSize((int) succeeded + 1);
    }

    private List<JsonNode> eventsFor(String aggregateId) {
        return jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE aggregate_id = ? "
                        + "ORDER BY created_at, id", String.class, aggregateId).stream()
                .map(jsonMapper::readTree)
                .toList();
    }

    private PostedTransaction capture(UUID shipment, long amount) {
        return ledger.post(command(key(), shipment, debit(PSP, amount), credit(ESCROW, amount)));
    }

    private static PostTransactionCommand command(String key, UUID shipment, EntryCommand... entries) {
        return new PostTransactionCommand(key, "TEST_TRANSACTION", shipment, null, List.of(entries));
    }

    private static PostTransactionCommand reversal(UUID original, UUID shipment, EntryCommand... entries) {
        return new PostTransactionCommand(key(), "TEST_REVERSAL", shipment, original, List.of(entries));
    }

    private static EntryCommand debit(String account, long amount) {
        return new EntryCommand(account, Direction.DEBIT, amount, "TRY");
    }

    private static EntryCommand credit(String account, long amount) {
        return new EntryCommand(account, Direction.CREDIT, amount, "TRY");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String wallet(String name) {
        return "LIABILITY:" + name + ":" + UUID.randomUUID();
    }

    private int count(String fromAndWhere, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromAndWhere, Integer.class, args);
    }

    private static void assertReason(Runnable action, Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
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
