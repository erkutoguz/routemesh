package com.routemesh.surgeservice.publish;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.routemesh.surgeservice.config.SurgeProperties;

/**
 * {@code pricing:surge:<bölge>} anahtarına çarpanı TTL'li yazar; pricing-service teklifte bu anahtarı okur.
 * Her hesaplama döngüsünde yeniden yazıldığı için TTL kayar: servis durursa anahtar süresi dolunca kalkar ve fiyat
 * çarpansız hesaplanır.
 */
public class RedisSurgePublisher implements SurgePublisher {

    public static final String KEY_PREFIX = "pricing:surge:";

    private final StringRedisTemplate redis;
    private final SurgeProperties properties;

    public RedisSurgePublisher(StringRedisTemplate redis, SurgeProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void publish(String zoneCode, int bps) {
        String key = KEY_PREFIX + zoneCode;
        if (bps <= SurgeProperties.NO_SURGE_BPS) {
            redis.delete(key);
            return;
        }
        redis.opsForValue().set(key, String.valueOf(bps), properties.redisTtl());
    }
}
