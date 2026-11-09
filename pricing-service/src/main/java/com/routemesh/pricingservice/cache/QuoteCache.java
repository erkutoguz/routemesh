package com.routemesh.pricingservice.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.routemesh.pricingservice.domain.Quote;
import com.routemesh.pricingservice.domain.QuoteStatus;

import tools.jackson.databind.json.JsonMapper;

@Component
public class QuoteCache {

    static final String KEY_PREFIX = "pricing:quote:";

    private static final Logger log = LoggerFactory.getLogger(QuoteCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    public QuoteCache(StringRedisTemplate redis, JsonMapper jsonMapper) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    public Optional<Quote> get(UUID id) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + id);
            if (json == null) {
                return Optional.empty();
            }
            Quote quote = jsonMapper.readValue(json, Quote.class);
            return quote.expiresAt().isAfter(Instant.now()) ? Optional.of(quote) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Quote cache read failed for {}", id, e);
            return Optional.empty();
        }
    }

    public void put(Quote quote) {
        if (quote.status() != QuoteStatus.ACTIVE) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), quote.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + quote.id(), jsonMapper.writeValueAsString(quote), ttl);
        } catch (RuntimeException e) {
            log.warn("Quote cache write failed for {}", quote.id(), e);
        }
    }

    public void evict(UUID id) {
        try {
            redis.delete(KEY_PREFIX + id);
        } catch (RuntimeException e) {
            log.warn("Quote cache eviction failed for {}", id, e);
        }
    }
}
