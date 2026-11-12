package com.routemesh.locationservice.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.routemesh.locationservice.TestcontainersConfiguration;
import com.routemesh.locationservice.store.CourierLocationStore;
import com.routemesh.locationservice.store.Watch;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.grpc.server.port=0",
        "routemesh.tracking.consumer-enabled=false",
        "routemesh.tracking.telemetry-enabled=false",
        "routemesh.tracking.sse.tick=100ms",
        "routemesh.tracking.sse.min-interval=1s",
        "routemesh.tracking.sse.heartbeat=1s",
        "routemesh.tracking.sse.health-interval=1s",
        "routemesh.tracking.sse.max-per-user=2",
        "routemesh.tracking.sse.max-total=3"})
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class TrackingStreamIntegrationTest {

    private static final double LAT = 41.0;
    private static final double LON = 29.0;
    private static final String EOF = "<EOF>";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private CourierLocationStore store;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TrackingStreamService streams;

    @Autowired
    private MeterRegistry meters;

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Sse> open = new java.util.ArrayList<>();

    private final String merchant = UUID.randomUUID().toString();
    private final String otherMerchant = UUID.randomUUID().toString();
    private final String courier = UUID.randomUUID().toString();
    private final String shipment = UUID.randomUUID().toString();
    private long seq;

    @BeforeEach
    void setUp() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        store.armWatch(new Watch(shipment, courier, Watch.DELIVERY, LAT, LON, merchant, 6));
    }

    @AfterEach
    void tearDown() {
        open.forEach(Sse::close);
        // Sunucu kopan istemciyi bir sonraki yazışta fark eder; sonraki test sınırları temiz görsün
        await().atMost(Duration.ofSeconds(10)).until(() -> streams.openStreams() == 0);
    }

    @Test
    void openConnectionsGaugeFollowsTheOpenStreams() throws Exception {
        assertThat(meters.get("routemesh.location.sse.connections").gauge().value()).isZero();

        Sse first = connect(shipment, merchantToken());
        connect(shipment, merchantToken());
        assertThat(meters.get("routemesh.location.sse.connections").gauge().value()).isEqualTo(2);

        first.close();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(meters.get("routemesh.location.sse.connections").gauge().value())
                        .isEqualTo(1));
    }

    // --- yetki ---

    @Test
    void withoutToken_isUnauthorized() throws Exception {
        assertThat(connect(shipment, null).status).isEqualTo(401);
    }

    @Test
    void nonMerchantRole_isForbidden() throws Exception {
        assertThat(connect(shipment, TestJwts.token(courier, "COURIER")).status).isEqualTo(403);
        assertThat(connect(shipment, TestJwts.token(merchant, "OPS")).status).isEqualTo(403);
    }

    @Test
    void anotherMerchantsShipmentAndUnknownShipment_areNotFound() throws Exception {
        assertThat(connect(shipment, TestJwts.token(otherMerchant, "MERCHANT")).status).isEqualTo(404);
        assertThat(connect(UUID.randomUUID().toString(), TestJwts.token(merchant, "MERCHANT")).status).isEqualTo(404);
    }

    @Test
    void shipmentWithoutAnActiveWatch_isNotFound() throws Exception {
        store.clearWatch(shipment, "DELIVERED");

        assertThat(connect(shipment, merchantToken()).status).isEqualTo(404);
    }

    // --- akış ---

    @Test
    void owner_getsAnUnbufferedEventStreamThatStartsImmediately() throws Exception {
        Sse sse = connect(shipment, merchantToken());

        assertThat(sse.status).isEqualTo(200);
        assertThat(sse.header("content-type")).startsWith("text/event-stream");
        assertThat(sse.header("x-accel-buffering")).isEqualTo("no");
        assertThat(sse.header("cache-control")).contains("no-cache");
        sse.awaitLine(line -> line.contains("connected"), 5);
    }

    @Test
    void locationsArePushedWithDistanceToTheCurrentTarget() throws Exception {
        Sse sse = connect(shipment, merchantToken());

        ping(LAT + 0.002);

        String data = sse.awaitEvent("location", 5);
        assertThat(data).contains("\"latitude\":" + (LAT + 0.002)).contains("\"target\":\"DELIVERY\"")
                .contains("\"distanceMeters\":222.");
        assertThat(sse.lastId).matches("\\d+:\\d+");
    }

    @Test
    void locationEvents_areThrottledAndTheLatestPositionWins() throws Exception {
        Sse sse = connect(shipment, merchantToken());

        ping(LAT + 0.001);
        sse.awaitEvent("location", 5);

        ping(LAT + 0.002);
        ping(LAT + 0.003);

        assertThat(sse.countEvents("location", 600)).isZero();
        assertThat(sse.awaitEvent("location", 5)).contains("\"latitude\":" + (LAT + 0.003));
    }

    @Test
    void approachingEventIsSentOnce() throws Exception {
        Sse sse = connect(shipment, merchantToken());

        ping(LAT + 0.00085);

        String data = sse.awaitEvent("approaching", 5);
        assertThat(data).contains("\"kind\":\"DELIVERY\"").contains("\"distanceMeters\":94.5");
        // Kafka hatasından sonra yeniden silahlanıp tekrar tetiklenirse akışa ikinci kez gitmez
        store.rearm(new com.routemesh.locationservice.store.ProximityHit(courier, shipment, Watch.DELIVERY, 94.5));
        ping(LAT + 0.0008);
        assertThat(sse.countEvents("approaching", 1500)).isZero();
    }

    @Test
    void terminalStatus_sendsEndAndClosesTheStream() throws Exception {
        Sse sse = connect(shipment, merchantToken());
        sse.awaitLine(line -> line.contains("connected"), 5);

        store.clearWatch(shipment, "DELIVERED");

        assertThat(sse.awaitEvent("end", 5)).isEqualTo("{\"reason\":\"DELIVERED\"}");
        sse.awaitLine(EOF::equals, 5);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(subscribers("watch:end:" + shipment)).isZero());
    }

    @Test
    void lostEndMessage_isCaughtByTheHealthCheck() throws Exception {
        Sse sse = connect(shipment, merchantToken());

        redis.delete("watch:shipment:" + shipment);

        assertThat(sse.awaitEvent("end", 5)).isEqualTo("{\"reason\":\"ENDED\"}");
    }

    @Test
    void streamEndsWhenTheTokenExpires() throws Exception {
        Sse sse = connect(shipment, TestJwts.token(merchant, 2, "MERCHANT"));

        assertThat(sse.awaitEvent("end", 6)).isEqualTo("{\"reason\":\"TOKEN_EXPIRED\"}");
    }

    @Test
    void heartbeatKeepsAnIdleStreamAlive() throws Exception {
        Sse sse = connect(shipment, merchantToken());

        sse.awaitLine(line -> line.contains("heartbeat"), 5);
    }

    @Test
    void subscriptionIsRemovedWhenTheLastClientLeaves() throws Exception {
        Sse first = connect(shipment, merchantToken());
        Sse second = connect(shipment, merchantToken());
        first.awaitLine(line -> line.contains("connected"), 5);
        assertThat(subscribers("courier:loc:" + courier)).isEqualTo(1);

        first.close();
        second.close();

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(subscribers("courier:loc:" + courier)).isZero();
            assertThat(subscribers("watch:end:" + shipment)).isZero();
        });
    }

    // --- sınırlar ---

    @Test
    void perUserLimit_returns429_andAClosedStreamFreesTheSlot() throws Exception {
        Sse first = connect(shipment, merchantToken());
        connect(shipment, merchantToken());

        assertThat(connect(shipment, merchantToken()).status).isEqualTo(429);
        // başka kullanıcı etkilenmez
        assertThat(connect(otherShipment(), TestJwts.token(otherMerchant, "MERCHANT")).status).isEqualTo(200);

        first.close();

        await().atMost(Duration.ofSeconds(8)).untilAsserted(
                () -> assertThat(connect(shipment, merchantToken()).status).isEqualTo(200));
    }

    @Test
    void totalLimit_returns429ForEveryone() throws Exception {
        connect(shipment, merchantToken());
        connect(shipment, merchantToken());
        connect(otherShipment(), TestJwts.token(otherMerchant, "MERCHANT"));

        assertThat(connect(otherShipment(), TestJwts.token(otherMerchant, "MERCHANT")).status).isEqualTo(429);
    }

    // --- yardımcılar ---

    private String merchantToken() {
        return TestJwts.token(merchant, "MERCHANT");
    }

    private String otherShipment() {
        String id = "00000000-0000-4000-8000-" + String.format("%012d", 1);
        store.armWatch(new Watch(id, UUID.randomUUID().toString(), Watch.DELIVERY, LAT, LON, otherMerchant, 6));
        return id;
    }

    private void ping(double latitude) {
        store.ingest(courier, 1, ++seq, latitude, LON);
    }

    private long subscribers(String channel) {
        Long count = redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "return redis.call('PUBSUB', 'NUMSUB', KEYS[1])[2]", Long.class), List.of(channel));
        return count == null ? 0 : count;
    }

    private Sse connect(String shipmentId, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/tracking/" + shipmentId + "/stream"))
                .header("Accept", "text/event-stream");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<Stream<String>> response = client.send(request.build(), HttpResponse.BodyHandlers.ofLines());
        Sse sse = new Sse(response);
        open.add(sse);
        return sse;
    }

    private static final class Sse {

        final HttpResponse<Stream<String>> response;
        final int status;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        volatile String lastId;

        Sse(HttpResponse<Stream<String>> response) {
            this.response = response;
            this.status = response.statusCode();
            Thread.startVirtualThread(() -> {
                try {
                    response.body().forEach(lines::add);
                } catch (RuntimeException ignored) {
                    // istemci kapattı
                } finally {
                    lines.add(EOF);
                }
            });
        }

        String header(String name) {
            return response.headers().firstValue(name).orElse("");
        }

        String awaitLine(java.util.function.Predicate<String> predicate, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (true) {
                String line = lines.poll(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
                if (line == null) {
                    throw new AssertionError("expected line did not arrive within " + seconds + "s");
                }
                if (predicate.test(line)) {
                    return line;
                }
                if (EOF.equals(line)) {
                    throw new AssertionError("stream closed before the expected line arrived");
                }
            }
        }

        /** @return the data of the next event with this name */
        String awaitEvent(String name, int seconds) throws InterruptedException {
            awaitLine(("event:" + name)::equals, seconds);
            String data = null;
            while (true) {
                String line = awaitLine(l -> true, seconds);
                if (line.isEmpty() || EOF.equals(line)) {
                    return data;
                }
                if (line.startsWith("data:")) {
                    data = line.substring("data:".length());
                } else if (line.startsWith("id:")) {
                    lastId = line.substring("id:".length());
                }
            }
        }

        long countEvents(String name, long millis) throws InterruptedException {
            long count = 0;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() < deadline) {
                String line = lines.poll(50, TimeUnit.MILLISECONDS);
                if (("event:" + name).equals(line)) {
                    count++;
                }
            }
            return count;
        }

        void close() {
            response.body().close();
        }
    }
}
