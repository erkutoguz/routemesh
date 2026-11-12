package com.routemesh.surgeservice.publish;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.routemesh.surgeservice.config.SurgeProperties;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code pricing:surge:<bölge>} anahtarına çarpanı TTL'li yazar; pricing-service teklifte bu anahtarı okur.
 * Her hesaplama döngüsünde yeniden yazıldığı için TTL kayar: servis durursa anahtar süresi dolunca kalkar ve fiyat
 * çarpansız hesaplanır.
 */
public class RedisSurgePublisher implements SurgePublisher {

    public static final String KEY_PREFIX = "pricing:surge:";

    private final StringRedisTemplate redis;
    private final SurgeProperties properties;
    private final MeterRegistry meters;
    // Gauge nesneyi zayıf tuttuğu için değerler burada güçlü referansla durur; bölge kümesi sınırlıdır (fiyat bölgeleri)
    private final Map<String, AtomicInteger> multipliers = new ConcurrentHashMap<>();

    public RedisSurgePublisher(StringRedisTemplate redis, SurgeProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.meters = meters;
    }

    @Override
    public void publish(String zoneCode, int bps) {
        multipliers.computeIfAbsent(zoneCode, zone -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder("routemesh.surge.multiplier.bps", value, AtomicInteger::get)
                    .tag("zoneCode", zone).register(meters);
            return value;
        }).set(bps);
        String key = KEY_PREFIX + zoneCode;
        if (bps <= SurgeProperties.NO_SURGE_BPS) {
            redis.delete(key);
            return;
        }
        redis.opsForValue().set(key, String.valueOf(bps), properties.redisTtl());
    }
}
