package com.routemesh.locationservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.routemesh.locationservice.TestcontainersConfiguration;
import com.routemesh.locationservice.config.LocationProperties;
import com.routemesh.locationservice.config.TrackingProperties;

@DataRedisTest
@EnableConfigurationProperties({LocationProperties.class, TrackingProperties.class})
@Import({TestcontainersConfiguration.class, RedisCourierLocationStore.class})
class ProximityWatchStoreTest {

    private static final double LAT = 41.0;
    private static final double LON = 29.0;
    // 1 enlem derecesi ~111.2 km: 0.00085 ~94.5 m (içeride), 0.00095 ~105.7 m (dışarıda)
    private static final double INSIDE = LAT + 0.00085;
    private static final double OUTSIDE = LAT + 0.00095;

    @Autowired
    private RedisCourierLocationStore store;

    @Autowired
    private StringRedisTemplate redis;

    private long seq;

    @BeforeEach
    void flush() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        seq = 0;
    }

    private Watch watch(String shipment, String courier, String kind, long version) {
        return new Watch(shipment, courier, kind, LAT, LON, "merchant-1", version);
    }

    private IngestResult ping(String courier, double lat) {
        return store.ingest(courier, 1, ++seq, lat, LON);
    }

    @Test
    void courierWithoutWatch_neverTriggers() {
        assertThat(ping("c1", LAT).hit()).isNull();
    }

    @Test
    void courierOutsideTheRadius_doesNotTrigger_insideTriggersWithDistance() {
        assertThat(store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6))).isTrue();

        assertThat(ping("c1", OUTSIDE).hit()).isNull();
        IngestResult inside = ping("c1", INSIDE);

        assertThat(inside.accepted()).isTrue();
        assertThat(inside.hit().shipmentId()).isEqualTo("s1");
        assertThat(inside.hit().kind()).isEqualTo(Watch.DELIVERY);
        assertThat(inside.hit().courierId()).isEqualTo("c1");
        assertThat(inside.hit().distanceMeters()).isCloseTo(94.5, within(1.0));
    }

    @Test
    void watchFiresOnlyOnce() {
        store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6));

        assertThat(ping("c1", INSIDE).hit()).isNotNull();
        assertThat(ping("c1", INSIDE).hit()).isNull();
        assertThat(ping("c1", LAT).hit()).isNull();
    }

    @Test
    void stalePacket_doesNotTrigger() {
        store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6));
        store.ingest("c1", 1, 10, OUTSIDE, LON);

        IngestResult stale = store.ingest("c1", 1, 9, INSIDE, LON);

        assertThat(stale.accepted()).isFalse();
        assertThat(stale.hit()).isNull();
    }

    @Test
    void rearm_letsTheNextPacketTriggerAgain() {
        store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6));
        ProximityHit hit = ping("c1", INSIDE).hit();

        store.rearm(hit);

        assertThat(ping("c1", INSIDE).hit()).isNotNull();
    }

    @Test
    void rearm_ofAnOldWatch_doesNotTouchTheCurrentOne() {
        store.armWatch(watch("s1", "c1", Watch.PICKUP, 5));
        ProximityHit pickupHit = ping("c1", INSIDE).hit();
        store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6));
        ping("c1", INSIDE); // DELIVERY de tetiklendi

        store.rearm(pickupHit);

        assertThat(ping("c1", INSIDE).hit()).isNull();
    }

    @Test
    void redeliveredEventDoesNotResetAFiredWatch_newerVersionReplacesIt() {
        store.armWatch(watch("s1", "c1", Watch.PICKUP, 5));
        ping("c1", INSIDE);

        assertThat(store.armWatch(watch("s1", "c1", Watch.PICKUP, 5))).isFalse();
        assertThat(ping("c1", INSIDE).hit()).isNull();

        assertThat(store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6))).isTrue();
        assertThat(ping("c1", INSIDE).hit().kind()).isEqualTo(Watch.DELIVERY);
    }

    @Test
    void olderVersionOfTheSameShipment_isIgnored() {
        store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6));

        assertThat(store.armWatch(watch("s1", "c1", Watch.PICKUP, 5))).isFalse();
        assertThat(ping("c1", INSIDE).hit().kind()).isEqualTo(Watch.DELIVERY);
    }

    @Test
    void newShipmentForTheSameCourier_replacesTheWatch() {
        store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6));

        assertThat(store.armWatch(watch("s2", "c1", Watch.PICKUP, 2))).isTrue();
        assertThat(ping("c1", INSIDE).hit().shipmentId()).isEqualTo("s2");
    }

    @Test
    void clearWatch_removesTheWatch_andLateEventsCannotRecreateIt() {
        store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6));

        store.clearWatch("s1", "DELIVERED");

        assertThat(redis.hasKey(RedisCourierLocationStore.WATCH_KEY_PREFIX + "c1")).isFalse();
        assertThat(ping("c1", INSIDE).hit()).isNull();
        assertThat(store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6))).isFalse();
    }

    @Test
    void clearWatch_leavesTheCouriersLocationForTheNextDispatch() {
        store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6));
        ping("c1", INSIDE);

        store.clearWatch("s1", "DELIVERED");

        assertThat(store.findNearest(LAT, LON, 1000, 10)).extracting(NearbyCourier::courierId).contains("c1");
    }

    @Test
    void clearWatch_ofAShipmentTheCourierLeft_doesNotRemoveTheNewShipmentsWatch() {
        store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6));
        store.armWatch(watch("s2", "c1", Watch.PICKUP, 2));

        store.clearWatch("s1", "DELIVERED");

        assertThat(redis.hasKey(RedisCourierLocationStore.WATCH_KEY_PREFIX + "c1")).isTrue();
        assertThat(ping("c1", INSIDE).hit().shipmentId()).isEqualTo("s2");
    }

    @Test
    void clearWatch_ofUnknownShipment_isHarmless() {
        store.clearWatch("never-armed", "DELIVERED");

        assertThat(store.armWatch(watch("never-armed", "c1", Watch.PICKUP, 1))).isFalse();
    }

    @Test
    void watchesExpireOnTheirOwn() {
        store.armWatch(watch("s1", "c1", Watch.DELIVERY, 6));

        Long ttl = redis.getExpire(RedisCourierLocationStore.WATCH_KEY_PREFIX + "c1", java.util.concurrent.TimeUnit.HOURS);

        assertThat(ttl).isBetween(47L, 48L);
    }
}
