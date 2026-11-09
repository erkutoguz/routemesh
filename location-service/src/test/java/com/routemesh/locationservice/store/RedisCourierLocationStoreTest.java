package com.routemesh.locationservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.routemesh.locationservice.TestcontainersConfiguration;
import com.routemesh.locationservice.config.LocationProperties;

@DataRedisTest
@EnableConfigurationProperties(LocationProperties.class)
@Import({TestcontainersConfiguration.class, RedisCourierLocationStore.class})
class RedisCourierLocationStoreTest {

    private static final double TARGET_LAT = 41.0;
    private static final double TARGET_LON = 29.0;

    @Autowired
    private RedisCourierLocationStore store;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void flush() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    void ingest_firstPacket_isAccepted() {
        assertThat(store.ingest("c1", 1, 5, TARGET_LAT, TARGET_LON)).isTrue();
    }

    @Test
    void ingest_sameSequence_isRejected() {
        store.ingest("c1", 1, 5, TARGET_LAT, TARGET_LON);

        assertThat(store.ingest("c1", 1, 5, TARGET_LAT, TARGET_LON)).isFalse();
    }

    @Test
    void ingest_lowerSequence_isRejected() {
        store.ingest("c1", 1, 5, TARGET_LAT, TARGET_LON);

        assertThat(store.ingest("c1", 1, 4, TARGET_LAT, TARGET_LON)).isFalse();
    }

    @Test
    void ingest_higherSequence_isAccepted() {
        store.ingest("c1", 1, 5, TARGET_LAT, TARGET_LON);

        assertThat(store.ingest("c1", 1, 6, TARGET_LAT, TARGET_LON)).isTrue();
    }

    @Test
    void ingest_newEpoch_isAcceptedEvenWithLowerSequence() {
        store.ingest("c1", 1, 99, TARGET_LAT, TARGET_LON);

        assertThat(store.ingest("c1", 2, 1, TARGET_LAT, TARGET_LON)).isTrue();
    }

    @Test
    void ingest_olderEpoch_isRejected() {
        store.ingest("c1", 2, 1, TARGET_LAT, TARGET_LON);

        assertThat(store.ingest("c1", 1, 99, TARGET_LAT, TARGET_LON)).isFalse();
    }

    @Test
    void ingest_rejectedPacket_doesNotMoveCourier() {
        store.ingest("c1", 1, 5, TARGET_LAT + 0.001, TARGET_LON);
        store.ingest("c1", 1, 4, TARGET_LAT + 0.01, TARGET_LON);

        List<NearbyCourier> result = store.findNearest(TARGET_LAT, TARGET_LON, 500, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).distanceMeters()).isCloseTo(111.2, within(2.0));
    }

    @Test
    void findNearest_returnsCouriersInRadiusSortedByDistance() {
        store.ingest("far", 1, 1, TARGET_LAT + 0.01, TARGET_LON);     // ~1112 m
        store.ingest("near", 1, 1, TARGET_LAT + 0.001, TARGET_LON);   // ~111 m
        store.ingest("outside", 1, 1, TARGET_LAT + 0.1, TARGET_LON);  // ~11 km

        List<NearbyCourier> result = store.findNearest(TARGET_LAT, TARGET_LON, 2000, 10);

        assertThat(result).extracting(NearbyCourier::courierId).containsExactly("near", "far");
        assertThat(result.get(0).distanceMeters()).isCloseTo(111.2, within(2.0));
        assertThat(result.get(1).distanceMeters()).isCloseTo(1112.0, within(5.0));
    }

    @Test
    void findNearest_respectsLimit() {
        store.ingest("a", 1, 1, TARGET_LAT + 0.001, TARGET_LON);
        store.ingest("b", 1, 1, TARGET_LAT + 0.002, TARGET_LON);
        store.ingest("c", 1, 1, TARGET_LAT + 0.003, TARGET_LON);

        List<NearbyCourier> result = store.findNearest(TARGET_LAT, TARGET_LON, 2000, 2);

        assertThat(result).extracting(NearbyCourier::courierId).containsExactly("a", "b");
    }

    @Test
    void findNearest_noCouriers_returnsEmptyList() {
        assertThat(store.findNearest(TARGET_LAT, TARGET_LON, 2000, 10)).isEmpty();
    }

    @Test
    void findNearest_excludesStaleCouriers_withoutConsumingLimit() {
        store.ingest("stale-near", 1, 1, TARGET_LAT + 0.001, TARGET_LON);
        store.ingest("fresh-far", 1, 1, TARGET_LAT + 0.01, TARGET_LON);
        redis.opsForZSet().add(RedisCourierLocationStore.LASTSEEN_KEY, "stale-near", 0);

        List<NearbyCourier> result = store.findNearest(TARGET_LAT, TARGET_LON, 2000, 1);

        assertThat(result).extracting(NearbyCourier::courierId).containsExactly("fresh-far");
    }

    @Test
    void ingest_setsExpiryOnStateKey() {
        store.ingest("c1", 1, 1, TARGET_LAT, TARGET_LON);

        Long ttlSeconds = redis.getExpire(RedisCourierLocationStore.STATE_KEY_PREFIX + "c1");

        assertThat(ttlSeconds).isBetween(86_000L, 86_400L);
    }

    @Test
    void evictStale_removesStaleCourierFromGeoAndLastSeen_keepsFreshOnes() {
        store.ingest("stale", 1, 1, TARGET_LAT, TARGET_LON);
        store.ingest("fresh", 1, 1, TARGET_LAT, TARGET_LON);
        redis.opsForZSet().add(RedisCourierLocationStore.LASTSEEN_KEY, "stale", 0);

        long removed = store.evictStale();

        assertThat(removed).isEqualTo(1);
        assertThat(redis.opsForZSet().score(RedisCourierLocationStore.GEO_KEY, "stale")).isNull();
        assertThat(redis.opsForZSet().score(RedisCourierLocationStore.LASTSEEN_KEY, "stale")).isNull();
        assertThat(redis.opsForZSet().score(RedisCourierLocationStore.GEO_KEY, "fresh")).isNotNull();
        assertThat(redis.opsForZSet().score(RedisCourierLocationStore.LASTSEEN_KEY, "fresh")).isNotNull();
    }

    @Test
    void evictStale_keepsStateSoOldPacketsAreStillRejected() {
        store.ingest("c1", 1, 5, TARGET_LAT, TARGET_LON);
        redis.opsForZSet().add(RedisCourierLocationStore.LASTSEEN_KEY, "c1", 0);

        store.evictStale();

        assertThat(store.ingest("c1", 1, 5, TARGET_LAT, TARGET_LON)).isFalse();
    }

    @Test
    void evictStale_removesMoreThanOneBatch() {
        int count = RedisCourierLocationStore.EVICTION_BATCH_SIZE + 500;
        for (int i = 0; i < count; i++) {
            redis.opsForZSet().add(RedisCourierLocationStore.GEO_KEY, "c" + i, i);
            redis.opsForZSet().add(RedisCourierLocationStore.LASTSEEN_KEY, "c" + i, 0);
        }

        long removed = store.evictStale();

        assertThat(removed).isEqualTo(count);
        assertThat(redis.opsForZSet().zCard(RedisCourierLocationStore.GEO_KEY)).isZero();
        assertThat(redis.opsForZSet().zCard(RedisCourierLocationStore.LASTSEEN_KEY)).isZero();
    }

    @Test
    void evictStale_nothingStale_returnsZero() {
        store.ingest("c1", 1, 1, TARGET_LAT, TARGET_LON);

        assertThat(store.evictStale()).isZero();
    }
}
