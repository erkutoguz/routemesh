package com.routemesh.locationservice.store;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.routemesh.locationservice.config.LocationProperties;

@Component
public class RedisCourierLocationStore implements CourierLocationStore {

    static final String GEO_KEY = "courier:geo";
    static final String STATE_KEY_PREFIX = "courier:state:";
    static final String LASTSEEN_KEY = "courier:lastseen";
    static final int EVICTION_BATCH_SIZE = 1000;

    private final StringRedisTemplate redis;
    private final LocationProperties properties;

    private final RedisScript<Long> ingestScript =
            RedisScript.of(new ClassPathResource("redis/ingest.lua"), Long.class);

    private final RedisScript<Long> evictStaleScript =
            RedisScript.of(new ClassPathResource("redis/evict_stale.lua"), Long.class);

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> findNearestScript =
            RedisScript.of(new ClassPathResource("redis/find_nearest.lua"), List.class);

    public RedisCourierLocationStore(StringRedisTemplate redis, LocationProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public boolean ingest(String courierId, long sessionEpoch, long sequenceNumber,
                          double latitude, double longitude) {
        Long result = redis.execute(
                ingestScript,
                List.of(GEO_KEY, STATE_KEY_PREFIX + courierId, LASTSEEN_KEY),
                courierId,
                String.valueOf(sessionEpoch),
                String.valueOf(sequenceNumber),
                String.valueOf(longitude),
                String.valueOf(latitude),
                String.valueOf(properties.stateTtl().toMillis()));
        return result != null && result == 1L;
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
