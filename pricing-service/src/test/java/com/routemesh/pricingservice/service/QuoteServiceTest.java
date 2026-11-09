package com.routemesh.pricingservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.routemesh.pricingservice.TestcontainersConfiguration;
import com.routemesh.pricingservice.domain.CreateQuoteCommand;
import com.routemesh.pricingservice.domain.Quote;
import com.routemesh.pricingservice.domain.QuoteResult;
import com.routemesh.pricingservice.domain.QuoteStatus;
import com.routemesh.pricingservice.domain.RedeemQuoteCommand;
import com.routemesh.pricingservice.domain.ServiceLevel;
import com.routemesh.pricingservice.service.PricingException.Reason;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.grpc.server.port=0")
class QuoteServiceTest {

    private static final double ORIGIN_LAT = 41.0136;
    private static final double ORIGIN_LON = 28.9550;
    private static final double DEST_LAT = 40.9876;
    private static final double DEST_LON = 29.0369;
    private static final String ZONE_KEY = "pricing:surge:IST_ASIA_CORE";

    @Autowired
    private QuoteService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void clearSurge() {
        redis.delete(ZONE_KEY);
    }

    @Test
    void create_pricesFromZoneDistanceAndWeight() {
        QuoteResult result = service.create(command(key(), ServiceLevel.STANDARD, 5000));
        Quote quote = result.quote();

        assertThat(result.replayed()).isFalse();
        assertThat(quote.zoneCode()).isEqualTo("IST_ASIA_CORE");
        assertThat(quote.status()).isEqualTo(QuoteStatus.ACTIVE);
        assertThat(quote.distanceMeters()).isBetween(6000L, 9000L);
        assertThat(quote.slaMinutes()).isEqualTo(240);
        assertThat(quote.surgeBps()).isEqualTo(10000);
        assertThat(quote.currency()).isEqualTo("TRY");
        long km = PriceCalculator.billableKilometers(quote.distanceMeters());
        assertThat(quote.basePriceMinor()).isEqualTo(4500 + km * 600);
        assertThat(quote.finalAmountMinor()).isEqualTo(quote.basePriceMinor());
        assertThat(Duration.between(quote.createdAt(), quote.expiresAt())).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void create_expressIsPricedAtOnePointFiveTimesWithATighterSla() {
        Quote standard = service.create(command(key(), ServiceLevel.STANDARD, 3000)).quote();
        Quote express = service.create(command(key(), ServiceLevel.EXPRESS, 3000)).quote();

        assertThat(express.slaMinutes()).isEqualTo(120);
        assertThat(express.basePriceMinor()).isEqualTo(standard.basePriceMinor() * 15000 / 10000);
    }

    @Test
    void create_chargesStartedKilogramsAboveFiveKilograms() {
        Quote light = service.create(command(key(), ServiceLevel.STANDARD, 5000)).quote();
        Quote heavy = service.create(command(key(), ServiceLevel.STANDARD, 7200)).quote();

        assertThat(heavy.basePriceMinor() - light.basePriceMinor()).isEqualTo(3 * 500);
    }

    @Test
    void create_outsideTheServiceArea_isRejected() {
        assertReason(() -> service.create(new CreateQuoteCommand(key(), UUID.randomUUID(), ORIGIN_LAT, ORIGIN_LON,
                39.9334, 32.8597, ServiceLevel.STANDARD, 1000)), Reason.OUT_OF_SERVICE_AREA);
    }

    @Test
    void create_invalidInputs_areRejected() {
        UUID merchant = UUID.randomUUID();
        List<CreateQuoteCommand> invalid = List.of(
                new CreateQuoteCommand(" ", merchant, ORIGIN_LAT, ORIGIN_LON, DEST_LAT, DEST_LON, ServiceLevel.STANDARD, 1000),
                new CreateQuoteCommand(key(), merchant, 91, ORIGIN_LON, DEST_LAT, DEST_LON, ServiceLevel.STANDARD, 1000),
                new CreateQuoteCommand(key(), merchant, ORIGIN_LAT, 181, DEST_LAT, DEST_LON, ServiceLevel.STANDARD, 1000),
                new CreateQuoteCommand(key(), merchant, ORIGIN_LAT, ORIGIN_LON, Double.NaN, DEST_LON, ServiceLevel.STANDARD, 1000),
                new CreateQuoteCommand(key(), merchant, ORIGIN_LAT, ORIGIN_LON, DEST_LAT, -181, ServiceLevel.STANDARD, 1000),
                new CreateQuoteCommand(key(), merchant, ORIGIN_LAT, ORIGIN_LON, DEST_LAT, DEST_LON, null, 1000),
                new CreateQuoteCommand(key(), merchant, ORIGIN_LAT, ORIGIN_LON, DEST_LAT, DEST_LON, ServiceLevel.STANDARD, 0),
                new CreateQuoteCommand(key(), merchant, ORIGIN_LAT, ORIGIN_LON, DEST_LAT, DEST_LON, ServiceLevel.STANDARD, 30001));

        for (CreateQuoteCommand command : invalid) {
            assertReason(() -> service.create(command), Reason.INVALID_REQUEST);
        }
    }

    @Test
    void create_sameKeyAndPayload_isReplayedWithoutANewRow() {
        CreateQuoteCommand command = command(key(), ServiceLevel.STANDARD, 2000);
        Quote first = service.create(command).quote();

        QuoteResult second = service.create(command);

        assertThat(second.replayed()).isTrue();
        assertThat(second.quote().id()).isEqualTo(first.id());
        assertThat(count("quotes WHERE idempotency_key = ?", command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void create_sameKeyWithDifferentPayload_isConflict() {
        String key = key();
        service.create(command(key, ServiceLevel.STANDARD, 2000));

        assertReason(() -> service.create(command(key, ServiceLevel.EXPRESS, 2000)), Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void create_appliesTheSurgeFromRedis() {
        redis.opsForValue().set(ZONE_KEY, "15000");

        Quote quote = service.create(command(key(), ServiceLevel.STANDARD, 5000)).quote();

        assertThat(quote.surgeBps()).isEqualTo(15000);
        assertThat(quote.finalAmountMinor()).isEqualTo(quote.basePriceMinor() * 15000 / 10000);
    }

    @Test
    void create_clampsSurgeToTheConfiguredRange_andIgnoresGarbage() {
        redis.opsForValue().set(ZONE_KEY, "99999");
        assertThat(service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote().surgeBps()).isEqualTo(25000);

        redis.opsForValue().set(ZONE_KEY, "5000");
        assertThat(service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote().surgeBps()).isEqualTo(10000);

        redis.opsForValue().set(ZONE_KEY, "not-a-number");
        assertThat(service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote().surgeBps()).isEqualTo(10000);
    }

    @Test
    void create_writesTheQuoteToTheCacheWithTheRemainingValidityAsTtl() {
        Quote quote = service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote();

        Long ttlSeconds = redis.getExpire("pricing:quote:" + quote.id(), TimeUnit.SECONDS);

        assertThat(ttlSeconds).isBetween(550L, 600L);
    }

    @Test
    void get_servesFromTheCache_andRepopulatesItOnAMiss() {
        Quote quote = service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote();
        String cacheKey = "pricing:quote:" + quote.id();

        assertThat(service.get(quote.id())).isEqualTo(quote);

        redis.delete(cacheKey);
        assertThat(redis.hasKey(cacheKey)).isFalse();
        assertThat(service.get(quote.id()).id()).isEqualTo(quote.id());
        assertThat(redis.hasKey(cacheKey)).isTrue();
    }

    @Test
    void get_expiredQuote_isReportedAsExpiredAndNotCached() {
        UUID id = insertQuote("now() - interval '20 minutes'", "now() - interval '10 minutes'");

        assertThat(service.get(id).status()).isEqualTo(QuoteStatus.EXPIRED);
        assertThat(redis.hasKey("pricing:quote:" + id)).isFalse();
    }

    @Test
    void get_unknownQuote_isNotFound() {
        assertReason(() -> service.get(UUID.randomUUID()), Reason.NOT_FOUND);
    }

    @Test
    void redeem_marksTheQuoteRedeemedAndEvictsTheCache() {
        Quote quote = service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote();
        UUID shipment = UUID.randomUUID();
        assertThat(redis.hasKey("pricing:quote:" + quote.id())).isTrue();

        QuoteResult result = service.redeem(new RedeemQuoteCommand(quote.id(), "redeem-" + shipment, shipment));

        assertThat(result.replayed()).isFalse();
        assertThat(result.quote().status()).isEqualTo(QuoteStatus.REDEEMED);
        assertThat(result.quote().redeemedShipmentId()).isEqualTo(shipment);
        assertThat(redis.hasKey("pricing:quote:" + quote.id())).isFalse();
        assertThat(service.get(quote.id()).status()).isEqualTo(QuoteStatus.REDEEMED);
    }

    @Test
    void redeem_sameKeyAndShipment_isReplayed() {
        Quote quote = service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote();
        RedeemQuoteCommand command = new RedeemQuoteCommand(quote.id(), key(), UUID.randomUUID());
        service.redeem(command);

        QuoteResult replay = service.redeem(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.quote().status()).isEqualTo(QuoteStatus.REDEEMED);
    }

    @Test
    void redeem_secondRedemptionWithAnotherKeyOrShipment_isRejected() {
        Quote quote = service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote();
        UUID shipment = UUID.randomUUID();
        String redemptionKey = key();
        service.redeem(new RedeemQuoteCommand(quote.id(), redemptionKey, shipment));

        assertReason(() -> service.redeem(new RedeemQuoteCommand(quote.id(), key(), shipment)),
                Reason.ALREADY_REDEEMED);
        assertReason(() -> service.redeem(new RedeemQuoteCommand(quote.id(), redemptionKey, UUID.randomUUID())),
                Reason.ALREADY_REDEEMED);
    }

    @Test
    void redeem_aShipmentOrKeyAlreadyUsedForAnotherQuote_isRejected() {
        Quote first = service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote();
        Quote second = service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote();
        UUID shipment = UUID.randomUUID();
        String redemptionKey = key();
        service.redeem(new RedeemQuoteCommand(first.id(), redemptionKey, shipment));

        assertReason(() -> service.redeem(new RedeemQuoteCommand(second.id(), key(), shipment)),
                Reason.ALREADY_REDEEMED);
        assertReason(() -> service.redeem(new RedeemQuoteCommand(second.id(), redemptionKey, UUID.randomUUID())),
                Reason.ALREADY_REDEEMED);
        assertThat(service.get(second.id()).status()).isEqualTo(QuoteStatus.ACTIVE);
    }

    @Test
    void redeem_expiredQuote_isRejected() {
        UUID id = insertQuote("now() - interval '20 minutes'", "now() - interval '10 minutes'");

        assertReason(() -> service.redeem(new RedeemQuoteCommand(id, key(), UUID.randomUUID())), Reason.QUOTE_EXPIRED);
    }

    @Test
    void redeem_unknownQuoteOrBlankKey() {
        assertReason(() -> service.redeem(new RedeemQuoteCommand(UUID.randomUUID(), key(), UUID.randomUUID())),
                Reason.NOT_FOUND);
        Quote quote = service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote();
        assertReason(() -> service.redeem(new RedeemQuoteCommand(quote.id(), " ", UUID.randomUUID())),
                Reason.INVALID_REQUEST);
    }

    @Test
    void concurrentRedemptions_ofOneQuote_exactlyOneWins() throws Exception {
        Quote quote = service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                try {
                    service.redeem(new RedeemQuoteCommand(quote.id(), key(), UUID.randomUUID()));
                    return true;
                } catch (PricingException e) {
                    assertThat(e.getReason()).isEqualTo(Reason.ALREADY_REDEEMED);
                    return false;
                }
            });
        }

        long winners = runConcurrently(tasks).stream().filter(Boolean::booleanValue).count();

        assertThat(winners).isEqualTo(1);
        assertThat(service.get(quote.id()).status()).isEqualTo(QuoteStatus.REDEEMED);
    }

    @Test
    void concurrentSameRedemption_isAppliedOnce() throws Exception {
        Quote quote = service.create(command(key(), ServiceLevel.STANDARD, 1000)).quote();
        RedeemQuoteCommand command = new RedeemQuoteCommand(quote.id(), key(), UUID.randomUUID());
        List<Callable<QuoteResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> service.redeem(command));
        }

        List<QuoteResult> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyCreates_produceOneQuote() throws Exception {
        CreateQuoteCommand command = command(key(), ServiceLevel.STANDARD, 1000);
        List<Callable<QuoteResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> service.create(command));
        }

        List<QuoteResult> results = runConcurrently(tasks);

        assertThat(results.stream().map(r -> r.quote().id()).distinct().count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(count("quotes WHERE idempotency_key = ?", command.idempotencyKey())).isEqualTo(1);
    }

    private static CreateQuoteCommand command(String key, ServiceLevel level, int weightGrams) {
        return new CreateQuoteCommand(key, UUID.randomUUID(), ORIGIN_LAT, ORIGIN_LON, DEST_LAT, DEST_LON, level,
                weightGrams);
    }

    private UUID insertQuote(String createdAt, String expiresAt) {
        return jdbc.queryForObject(
                "INSERT INTO quotes (idempotency_key, request_hash, merchant_id, origin_latitude, origin_longitude, "
                        + "destination_latitude, destination_longitude, zone_id, service_level, weight_grams, "
                        + "distance_meters, base_price_minor, surge_bps, final_amount_minor, currency, sla_minutes, "
                        + "created_at, expires_at) SELECT '" + UUID.randomUUID() + "', 'h', gen_random_uuid(), "
                        + "41.0, 28.9, 41.0, 29.0, z.id, 'STANDARD', 1000, 1000, 5500, 10000, 5500, 'TRY', 240, "
                        + createdAt + ", " + expiresAt + " FROM pricing_zones z WHERE z.code = 'IST_ASIA_CORE' "
                        + "RETURNING id", UUID.class);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private int count(String fromAndWhere, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromAndWhere, Integer.class, args);
    }

    private static void assertReason(Runnable action, Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(PricingException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
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
