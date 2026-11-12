package com.routemesh.surgeservice.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.routemesh.surgeservice.TestcontainersConfiguration;
import com.routemesh.surgeservice.config.SurgeProperties;

@DataRedisTest
@EnableConfigurationProperties(SurgeProperties.class)
@Import(TestcontainersConfiguration.class)
class RedisSurgePublisherTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SurgeProperties properties;

    private RedisSurgePublisher publisher;

    @BeforeEach
    void setUp() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        publisher = new RedisSurgePublisher(redis, properties);
    }

    @Test
    void writesThePricingKeyWithATenMinuteTtl() {
        publisher.publish("IST_ASIA_CORE", 15_000);

        assertThat(redis.opsForValue().get("pricing:surge:IST_ASIA_CORE")).isEqualTo("15000");
        Long ttl = redis.getExpire("pricing:surge:IST_ASIA_CORE", TimeUnit.SECONDS);
        assertThat(ttl).isBetween(Duration.ofMinutes(9).toSeconds(), Duration.ofMinutes(10).toSeconds());
    }

    @Test
    void publishingAgainSlidesTheTtlAndUpdatesTheValue() {
        publisher.publish("Z", 12_000);
        redis.expire("pricing:surge:Z", Duration.ofSeconds(30));

        publisher.publish("Z", 13_000);

        assertThat(redis.opsForValue().get("pricing:surge:Z")).isEqualTo("13000");
        assertThat(redis.getExpire("pricing:surge:Z", TimeUnit.SECONDS)).isGreaterThan(500);
    }

    @Test
    void noSurgeRemovesTheKeySoPricingFallsBackToTheNeutralMultiplier() {
        publisher.publish("Z", 20_000);

        publisher.publish("Z", 10_000);

        assertThat(redis.hasKey("pricing:surge:Z")).isFalse();
        publisher.publish("Z", 10_000); // olmayan anahtarı silmek zararsız
        assertThat(redis.hasKey("pricing:surge:Z")).isFalse();
    }

    @Test
    void zonesAreIndependent() {
        publisher.publish("A", 15_000);
        publisher.publish("B", 22_000);
        publisher.publish("A", 10_000);

        assertThat(redis.hasKey("pricing:surge:A")).isFalse();
        assertThat(redis.opsForValue().get("pricing:surge:B")).isEqualTo("22000");
    }
}
