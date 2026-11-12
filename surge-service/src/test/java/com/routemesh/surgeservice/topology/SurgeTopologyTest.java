package com.routemesh.surgeservice.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.routemesh.surgeservice.config.SurgeProperties;
import com.routemesh.surgeservice.publish.SurgePublisher;
import com.routemesh.surgeservice.zone.ZoneLocator;

import tools.jackson.databind.json.JsonMapper;

class SurgeTopologyTest {

    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");
    private static final Duration TICK = Duration.ofSeconds(30);

    // Bölge A: 40.9-41.1 enlem, 28.9-29.1 boylam. Bölge B: 41.2-41.3 enlem. Dışı: hizmet bölgesi değil.
    private static final double[] IN_A = {41.00, 29.00};
    private static final double[] IN_B = {41.25, 29.00};
    private static final double[] OUTSIDE = {39.90, 32.80};

    private final SurgeProperties properties = new SurgeProperties("courier.locations", "fleet.events",
            "shipment.events", Duration.ofMinutes(5), Duration.ofMinutes(15), TICK, 3, 25_000, 500, 1_000,
            Duration.ofMinutes(10), 0.005, Duration.ofHours(1), 3);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, List<Integer>> published = new HashMap<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();

    private TopologyTestDriver driver;
    private TestInputTopic<String, String> locations;
    private TestInputTopic<String, String> fleet;
    private TestInputTopic<String, String> shipments;

    @BeforeEach
    void setUp() throws Exception {
        ZoneLocator zones = (latitude, longitude) -> {
            if (latitude >= 40.9 && latitude < 41.1 && longitude >= 28.9 && longitude <= 29.1) {
                return Optional.of("A");
            }
            if (latitude >= 41.2 && latitude < 41.3 && longitude >= 28.9 && longitude <= 29.1) {
                return Optional.of("B");
            }
            return Optional.empty();
        };
        SurgePublisher publisher = (zone, bps) -> {
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("redis down");
            }
            published.computeIfAbsent(zone, z -> new ArrayList<>()).add(bps);
        };
        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "surge-test");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        config.put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory("surge-test").toString());
        driver = new TopologyTestDriver(SurgeTopology.build(properties, zones, publisher, mapper), config, T0);
        locations = driver.createInputTopic("courier.locations", new StringSerializer(), new StringSerializer());
        fleet = driver.createInputTopic("fleet.events", new StringSerializer(), new StringSerializer());
        shipments = driver.createInputTopic("shipment.events", new StringSerializer(), new StringSerializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    // --- çarpanın hesabı ---

    @Test
    void demandTwiceTheIdleSupply_publishesOnePointFive() {
        idleCouriers(3, IN_A, T0);
        orders(6, IN_A, T0);

        tick();

        assertThat(latest("A")).isEqualTo(15_000);
    }

    @Test
    void lessThanThreeOrders_meansNoSurge() {
        orders(2, IN_A, T0);

        tick();

        assertThat(latest("A")).isEqualTo(10_000);
    }

    @Test
    void theMultiplierIsCappedAtTwoPointFive() {
        idleCouriers(1, IN_A, T0);
        orders(30, IN_A, T0);

        tick();

        assertThat(latest("A")).isEqualTo(25_000);
    }

    @Test
    void anAbundantSupplyKeepsTheFloor() {
        idleCouriers(20, IN_A, T0);
        orders(5, IN_A, T0);

        tick();

        assertThat(latest("A")).isEqualTo(10_000);
    }

    // --- arz: yalnızca IDLE kuryeler ---

    @Test
    void onlyIdleCouriersCountAsSupply() {
        status("idle-1", "IDLE", T0);
        status("idle-2", "IDLE", T0);
        status("busy-1", "BUSY", T0);
        status("off-1", "OFFLINE", T0);
        for (String courier : List.of("idle-1", "idle-2", "busy-1", "off-1")) {
            ping(courier, IN_A, T0.plusSeconds(1));
        }
        orders(3, IN_A, T0.plusSeconds(2));

        tick();

        assertThat(latest("A")).isEqualTo(12_500); // arz 2, talep 3
    }

    @Test
    void aCourierWithoutAKnownStatusIsNotCounted() {
        ping("mystery", IN_A, T0);
        orders(3, IN_A, T0);

        tick();

        assertThat(latest("A")).isEqualTo(20_000); // arz 0 → 1 sayılır, oran 3
    }

    @Test
    void aCourierWhoTurnsBusyLeavesTheSupplyOnItsNextPing() {
        idleCouriers(3, IN_A, T0);
        orders(6, IN_A, T0);
        tick();
        assertThat(latest("A")).isEqualTo(15_000);

        status("c1", "BUSY", T0.plusSeconds(40));
        ping("c1", IN_A, T0.plusSeconds(41));
        tick();

        assertThat(latest("A")).isEqualTo(20_000); // arz 2, talep 6 → oran 3
    }

    @Test
    void supplyExpiresAfterFiveMinutesWithoutPings() {
        idleCouriers(3, IN_A, T0);
        orders(6, IN_A, T0);
        tick();
        assertThat(latest("A")).isEqualTo(15_000);

        advance(Duration.ofMinutes(6));

        assertThat(latest("A")).isEqualTo(25_000); // arz 0 → 1, oran 6 → tavan
    }

    // --- talep penceresi ve düşüş ---

    @Test
    void afterTheDemandWindowTheMultiplierDecaysOneStepPerTickAndThenDisappears() {
        idleCouriers(1, IN_A, T0);
        orders(6, IN_A, T0);
        tick();
        assertThat(latest("A")).isEqualTo(25_000);

        advance(Duration.ofMinutes(30));

        List<Integer> history = published.get("A");
        int firstDecay = history.indexOf(24_000);
        assertThat(firstDecay).isPositive();
        for (int i = firstDecay; i < history.size() - 1 && history.get(i) > 10_000; i++) {
            assertThat(history.get(i) - history.get(i + 1)).isEqualTo(1_000);
        }
        assertThat(history.get(history.size() - 1)).isEqualTo(10_000);
        int publishesAfterFloor = history.size();

        advance(Duration.ofMinutes(5));

        assertThat(published.get("A")).hasSize(publishesAfterFloor); // boşalan bölge durumdan kalkar, yayın durur
    }

    @Test
    void ordersJustInsideTheWindowStillCount() {
        orders(3, IN_A, T0);

        advance(Duration.ofMinutes(14));

        assertThat(latest("A")).isEqualTo(20_000);
    }

    // --- titreşim önleme ---

    @Test
    void smallFluctuationsDoNotMoveThePublishedMultiplier() {
        idleCouriers(20, IN_A, T0);
        orders(40, IN_A, T0);
        tick();
        assertThat(latest("A")).isEqualTo(15_000);

        orders(1, IN_A, T0.plusSeconds(35)); // hedef 15250: fark 250 < 500
        tick();

        assertThat(latest("A")).isEqualTo(15_000);

        orders(1, IN_A, T0.plusSeconds(65)); // hedef 15500: fark 500
        tick();

        assertThat(latest("A")).isEqualTo(15_500);
    }

    @Test
    void everyTickRepublishesTheCurrentValueSoTheRedisTtlKeepsSliding() {
        idleCouriers(3, IN_A, T0);
        orders(6, IN_A, T0);

        advance(Duration.ofMinutes(2));

        assertThat(published.get("A")).hasSize(4).containsOnly(15_000);
    }

    // --- bölgeler ---

    @Test
    void zonesAreIndependent() {
        idleCouriers(3, IN_B, T0);
        orders(6, IN_A, T0);
        orders(3, IN_B, T0);

        tick();

        assertThat(latest("A")).isEqualTo(25_000);  // arz yok
        assertThat(latest("B")).isEqualTo(10_000);  // arz 3, talep 3
    }

    @Test
    void eventsOutsideAnyZoneAreIgnored() {
        idleCouriers(3, OUTSIDE, T0);
        orders(10, OUTSIDE, T0);

        tick();

        assertThat(published).isEmpty();
    }

    // --- bozuk ve ilgisiz olaylar ---

    @Test
    void malformedAndForeignEventsAreSkipped() {
        locations.pipeInput("c1", "not json", T0);
        locations.pipeInput("c1", "{\"courierId\":\"c1\"}", T0);
        fleet.pipeInput("c1", "{broken", T0);
        fleet.pipeInput("c1", "{\"eventType\":\"OfferStatusChanged\",\"data\":{\"courierId\":\"c1\",\"toStatus\":\"OFFERED\"}}", T0);
        shipments.pipeInput("s1", "garbage", T0);
        shipments.pipeInput("s2", "{\"eventType\":\"ShipmentStatusChanged\",\"data\":{\"shipmentId\":\"s2\"}}", T0);
        shipments.pipeInput("s3", "{\"eventType\":\"ShipmentCreated\",\"data\":{\"shipmentId\":\"s3\"}}", T0); // koordinatsız eski olay

        tick();

        assertThat(published).isEmpty();
    }

    @Test
    void aPublisherFailureDoesNotStopTheNextTicks() {
        idleCouriers(3, IN_A, T0);
        orders(6, IN_A, T0);
        failuresLeft.set(1);

        advance(Duration.ofMinutes(1));

        assertThat(published.get("A")).containsExactly(15_000); // ilk yayın düştü, ikincisi geçti
    }

    @Test
    void repeatedPingsFromOneCourierCountAsASingleSupply() {
        status("c1", "IDLE", T0);
        ping("c1", IN_A, T0.plusSeconds(1));
        ping("c1", IN_A, T0.plusSeconds(2));
        ping("c1", IN_A, T0.plusSeconds(3));
        orders(3, IN_A, T0.plusSeconds(4));

        tick();

        assertThat(latest("A")).isEqualTo(20_000); // arz 1 (tekrarlayan konumlar tek kurye), talep 3
    }

    // --- yardımcılar ---

    private void tick() {
        driver.advanceWallClockTime(TICK);
    }

    /** Test sürücüsü büyük bir sıçramada zamanlayıcıyı tek sefer tetikler; gerçek ortamdaki gibi tick tick ilerletir. */
    private void advance(Duration duration) {
        for (long elapsed = 0; elapsed < duration.toMillis(); elapsed += TICK.toMillis()) {
            tick();
        }
    }

    private int latest(String zone) {
        List<Integer> history = published.get(zone);
        assertThat(history).as("published values of zone " + zone).isNotNull().isNotEmpty();
        return history.get(history.size() - 1);
    }

    private void idleCouriers(int count, double[] at, Instant time) {
        for (int i = 1; i <= count; i++) {
            status("c" + i, "IDLE", time);
            ping("c" + i, at, time.plusSeconds(1));
        }
    }

    private void orders(int count, double[] at, Instant time) {
        for (int i = 0; i < count; i++) {
            String id = "ship-" + System.nanoTime() + "-" + i;
            shipments.pipeInput(id, "{\"eventType\":\"ShipmentCreated\",\"data\":{\"shipmentId\":\"" + id
                    + "\",\"deliveryLatitude\":" + at[0] + ",\"deliveryLongitude\":" + at[1] + "}}", time);
        }
    }

    private void status(String courier, String toStatus, Instant time) {
        fleet.pipeInput(courier, "{\"eventType\":\"CourierStatusChanged\",\"data\":{\"courierId\":\"" + courier
                + "\",\"toStatus\":\"" + toStatus + "\"}}", time);
    }

    private void ping(String courier, double[] at, Instant time) {
        locations.pipeInput(courier, "{\"courierId\":\"" + courier + "\",\"latitude\":" + at[0] + ",\"longitude\":"
                + at[1] + "}", time);
    }
}
