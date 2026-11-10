package com.routemesh.locationservice.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.routemesh.locationservice.config.LocationProperties;
import com.routemesh.locationservice.config.TrackingProperties;

@Component
public class RedisCourierLocationStore implements CourierLocationStore {

    static final String GEO_KEY = "courier:geo";
    static final String STATE_KEY_PREFIX = "courier:state:";
    static final String LASTSEEN_KEY = "courier:lastseen";
    static final String WATCH_KEY_PREFIX = "courier:watch:";
    static final String SHIPMENT_INDEX_PREFIX = "watch:shipment:";
    static final String DONE_KEY_PREFIX = "watch:done:";
    static final int EVICTION_BATCH_SIZE = 1000;

    private final StringRedisTemplate redis;
    private final LocationProperties properties;
    private final TrackingProperties tracking;

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> ingestScript =
            RedisScript.of(new ClassPathResource("redis/ingest.lua"), List.class);

    private final RedisScript<Long> armWatchScript =
            RedisScript.of(new ClassPathResource("redis/arm_watch.lua"), Long.class);

    private final RedisScript<Long> clearWatchScript =
            RedisScript.of(new ClassPathResource("redis/clear_watch.lua"), Long.class);

    private final RedisScript<Long> rearmWatchScript =
            RedisScript.of(new ClassPathResource("redis/rearm_watch.lua"), Long.class);

    private final RedisScript<Long> evictStaleScript =
            RedisScript.of(new ClassPathResource("redis/evict_stale.lua"), Long.class);

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> findNearestScript =
            RedisScript.of(new ClassPathResource("redis/find_nearest.lua"), List.class);

    public RedisCourierLocationStore(StringRedisTemplate redis, LocationProperties properties,
                                     TrackingProperties tracking) {
        this.redis = redis;
        this.properties = properties;
        this.tracking = tracking;
    }

    @Override
    public IngestResult ingest(String courierId, long sessionEpoch, long sequenceNumber,
                               double latitude, double longitude) {
        List<?> result = redis.execute(
                ingestScript,
                List.of(GEO_KEY, STATE_KEY_PREFIX + courierId, LASTSEEN_KEY, WATCH_KEY_PREFIX + courierId),
                courierId,
                String.valueOf(sessionEpoch),
                String.valueOf(sequenceNumber),
                String.valueOf(longitude),
                String.valueOf(latitude),
                String.valueOf(properties.stateTtl().toMillis()));
        if (result == null || result.isEmpty() || ((Number) result.get(0)).longValue() == 0L) {
            return IngestResult.STALE;
        }
        if (((Number) result.get(0)).longValue() == 1L) {
            return IngestResult.ACCEPTED;
        }
        return new IngestResult(true, new ProximityHit(courierId, result.get(2).toString(),
                result.get(3).toString(), Double.parseDouble(result.get(1).toString())));
    }

    @Override
    public boolean armWatch(Watch watch) {
        long ttl = tracking.watchTtl().toMillis();
        Long result = redis.execute(
                armWatchScript,
                List.of(WATCH_KEY_PREFIX + watch.courierId(), SHIPMENT_INDEX_PREFIX + watch.shipmentId(),
                        DONE_KEY_PREFIX + watch.shipmentId()),
                watch.shipmentId(),
                watch.courierId(),
                watch.kind(),
                String.valueOf(watch.latitude()),
                String.valueOf(watch.longitude()),
                String.valueOf(tracking.approachRadiusMeters()),
                watch.merchantId(),
                String.valueOf(watch.version()),
                String.valueOf(ttl));
        return result != null && result == 1L;
    }

    @Override
    public void clearWatch(String shipmentId, String reason) {
        redis.execute(
                clearWatchScript,
                List.of(SHIPMENT_INDEX_PREFIX + shipmentId, DONE_KEY_PREFIX + shipmentId),
                shipmentId,
                String.valueOf(tracking.doneTtl().toMillis()),
                WATCH_KEY_PREFIX,
                reason);
    }

    @Override
    public Optional<WatchView> findWatch(String shipmentId) {
        String courierId = redis.opsForValue().get(SHIPMENT_INDEX_PREFIX + shipmentId);
        if (courierId == null) {
            return Optional.empty();
        }
        Map<Object, Object> watch = redis.opsForHash().entries(WATCH_KEY_PREFIX + courierId);
        if (!shipmentId.equals(watch.get("shipment"))) {
            return Optional.empty();
        }
        return Optional.of(new WatchView(shipmentId, courierId, String.valueOf(watch.get("kind")),
                Double.parseDouble(String.valueOf(watch.get("lat"))),
                Double.parseDouble(String.valueOf(watch.get("lon"))), String.valueOf(watch.get("merchant"))));
    }

    @Override
    public void rearm(ProximityHit hit) {
        redis.execute(rearmWatchScript, List.of(WATCH_KEY_PREFIX + hit.courierId()), hit.shipmentId(), hit.kind());
    }

    @Override
    public long evictStale() {
        long total = 0;
        Long removed;
        do {
            removed = redis.execute(
                    evictStaleScript,
                    List.of(GEO_KEY, LASTSEEN_KEY),
                    String.valueOf(properties.staleAfter().toMillis()),
                    String.valueOf(EVICTION_BATCH_SIZE));
            total += removed == null ? 0 : removed;
        } while (removed != null && removed == EVICTION_BATCH_SIZE);
        return total;
    }

    @Override
    public List<NearbyCourier> findNearest(double latitude, double longitude,
                                           double radiusMeters, int limit) {
        List<?> raw = redis.execute(
                findNearestScript,
                List.of(GEO_KEY, LASTSEEN_KEY),
                String.valueOf(longitude),
                String.valueOf(latitude),
                String.valueOf(radiusMeters),
                String.valueOf(limit),
                String.valueOf(properties.staleAfter().toMillis()));

        List<NearbyCourier> couriers = new ArrayList<>();
        if (raw == null) {
            return couriers;
        }
        for (int i = 0; i + 1 < raw.size(); i += 2) {
            couriers.add(new NearbyCourier(
                    raw.get(i).toString(),
                    Double.parseDouble(raw.get(i + 1).toString())));
        }
        return couriers;
    }
}
