package com.routemesh.pricingservice.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.routemesh.pricingservice.config.PricingProperties;

@Component
public class SurgeProvider {

    public static final int NO_SURGE_BPS = 10_000;
    static final String KEY_PREFIX = "pricing:surge:";

    private static final Logger log = LoggerFactory.getLogger(SurgeProvider.class);

    private final StringRedisTemplate redis;
    private final PricingProperties properties;

    public SurgeProvider(StringRedisTemplate redis, PricingProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public int surgeBps(String zoneCode) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + zoneCode);
            if (value == null) {
                return NO_SURGE_BPS;
            }
            int bps = Integer.parseInt(value.trim());
            return Math.min(Math.max(bps, NO_SURGE_BPS), properties.maxSurgeBps());
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid surge value for zone {}", zoneCode);
            return NO_SURGE_BPS;
        } catch (RuntimeException e) {
            log.warn("Surge lookup failed for zone {}, using no surge", zoneCode, e);
            return NO_SURGE_BPS;
        }
    }
}
