package com.routemesh.fleetservice.redis;

import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.routemesh.fleetservice.service.FleetException;
import com.routemesh.fleetservice.service.FleetException.Reason;

/**
 * Kurye başına tekdüze artan fencing token üretir. Taban değer veritabanındaki son token'dır; böylece Redis
 * verisini kaybetse bile üretilen token veritabanındakinin altına inmez.
 */
@Component
public class FenceTokens {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> nextScript =
            RedisScript.of(new ClassPathResource("redis/next_fence.lua"), Long.class);

    public FenceTokens(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long next(UUID courierId, long databaseFloor) {
        try {
            Long token = redis.execute(nextScript, List.of("fleet:fence:" + courierId),
                    Long.toString(databaseFloor));
            if (token == null) {
                throw new FleetException(Reason.UNAVAILABLE, "fence token could not be issued");
            }
            return token;
        } catch (DataAccessException e) {
            throw new FleetException(Reason.UNAVAILABLE, "fence token store is unavailable");
        }
    }
}
