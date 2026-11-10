package com.routemesh.fleetservice.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.routemesh.fleetservice.TestcontainersConfiguration;
import com.routemesh.fleetservice.service.FleetException;
import com.routemesh.fleetservice.service.FleetException.Reason;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"spring.grpc.server.port=0", "routemesh.fleet.lock-wait=400ms"})
class RedisPrimitivesTest {

    @Autowired
    private FenceTokens fenceTokens;

    @Autowired
    private CourierLock lock;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void tokensIncreaseStrictly() {
        UUID courier = UUID.randomUUID();

        assertThat(fenceTokens.next(courier, 0)).isEqualTo(1);
        assertThat(fenceTokens.next(courier, 1)).isEqualTo(2);
        assertThat(fenceTokens.next(courier, 2)).isEqualTo(3);
    }

    @Test
    void tokenNeverFallsBelowTheDatabaseFloorEvenAfterRedisLosesItsData() {
        UUID courier = UUID.randomUUID();
        assertThat(fenceTokens.next(courier, 40)).isEqualTo(41);

        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });

        assertThat(fenceTokens.next(courier, 41)).isEqualTo(42);
    }

    @Test
    void tokensAreIndependentPerCourier() {
        assertThat(fenceTokens.next(UUID.randomUUID(), 0)).isEqualTo(1);
        assertThat(fenceTokens.next(UUID.randomUUID(), 0)).isEqualTo(1);
    }

    @Test
    void lockIsExclusiveAndReleasedAfterwards() throws Exception {
        UUID courier = UUID.randomUUID();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        Runnable critical = () -> lock.withLock(courier, () -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            sleep(60);
            inside.decrementAndGet();
            return null;
        });

        Thread other = new Thread(critical);
        other.start();
        critical.run();
        other.join();

        assertThat(maxInside.get()).isEqualTo(1);
        assertThat(redis.hasKey(key(courier))).isFalse();
    }

    @Test
    void lockHeldByAnotherOwnerMakesCallersWaitThenFailAsUnavailable() {
        UUID courier = UUID.randomUUID();
        redis.opsForValue().set(key(courier), "someone-else", Duration.ofSeconds(30));
        AtomicInteger ran = new AtomicInteger();

        assertThatThrownBy(() -> lock.withLock(courier, ran::incrementAndGet))
                .isInstanceOfSatisfying(FleetException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE));

        assertThat(ran.get()).isZero();
        assertThat(redis.opsForValue().get(key(courier))).isEqualTo("someone-else");
    }

    @Test
    void lockOfACrashedOwnerExpiresAndCanBeTakenOver() {
        UUID courier = UUID.randomUUID();
        redis.opsForValue().set(key(courier), "crashed", Duration.ofMillis(150));

        assertThat(lock.withLock(courier, () -> "done")).isEqualTo("done");
    }

    @Test
    void releaseNeverDeletesALockThatNowBelongsToSomeoneElse() {
        UUID courier = UUID.randomUUID();

        lock.withLock(courier, () -> {
            redis.opsForValue().set(key(courier), "new-owner", Duration.ofSeconds(30));
            return null;
        });

        assertThat(redis.opsForValue().get(key(courier))).isEqualTo("new-owner");
    }

    private static String key(UUID courier) {
        return "fleet:lock:courier:" + courier;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
