package com.routemesh.fleetservice.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.routemesh.fleetservice.config.FleetProperties;
import com.routemesh.fleetservice.service.FleetException;
import com.routemesh.fleetservice.service.FleetException.Reason;

/**
 * Kurye başına dağıtık kilit (tek Redis düğümü). Kilit yalnızca çekişmeyi azaltır; doğruluğu veritabanı
 * koşulları ve fencing token sağlar. Kilit süresi dolup eski sahip geç kalsa bile yazması reddedilir.
 */
@Component
public class CourierLock {

    private static final Logger log = LoggerFactory.getLogger(CourierLock.class);
    private static final Duration RETRY_DELAY = Duration.ofMillis(25);

    private final StringRedisTemplate redis;
    private final FleetProperties properties;
    private final RedisScript<Long> unlockScript =
            RedisScript.of(new ClassPathResource("redis/unlock.lua"), Long.class);

    public CourierLock(StringRedisTemplate redis, FleetProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public <T> T withLock(UUID courierId, Supplier<T> action) {
        String key = "fleet:lock:courier:" + courierId;
        String owner = UUID.randomUUID().toString();
        acquire(key, owner);
        try {
            return action.get();
        } finally {
            release(key, owner);
        }
    }

    private void acquire(String key, String owner) {
        long deadline = System.nanoTime() + properties.lockWait().toNanos();
        try {
            while (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, properties.lockTtl()))) {
                if (System.nanoTime() >= deadline) {
                    throw new FleetException(Reason.UNAVAILABLE, "courier is being updated, retry shortly");
                }
                Thread.sleep(RETRY_DELAY.toMillis());
            }
        } catch (DataAccessException e) {
            throw new FleetException(Reason.UNAVAILABLE, "lock store is unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FleetException(Reason.UNAVAILABLE, "interrupted while waiting for the courier lock");
        }
    }

    private void release(String key, String owner) {
        try {
            redis.execute(unlockScript, List.of(key), owner);
        } catch (DataAccessException e) {
            log.warn("Could not release {}; it expires on its own", key, e);
        }
    }
}
