package com.routemesh.surgeservice.topology;

import java.util.HashMap;
import java.util.Map;

import com.routemesh.surgeservice.config.SurgeProperties;

/** Bir bölgenin durumu: IDLE kuryelerin son görülme zamanı, dakika kovalarında talep ve yayınlanan çarpan. */
public class ZoneState {

    public static final long BUCKET_MILLIS = 60_000;

    public Map<String, Long> idleCouriers = new HashMap<>();
    public Map<Long, Integer> demandByMinute = new HashMap<>();
    public int bps = SurgeProperties.NO_SURGE_BPS;

    public boolean hasNoActivity() {
        return idleCouriers.isEmpty() && demandByMinute.isEmpty();
    }

    public long demand() {
        return demandByMinute.values().stream().mapToLong(Integer::longValue).sum();
    }

    /** Pencere dışına düşenleri atar. */
    public void prune(long nowMillis, long supplyWindowMillis, long demandWindowMillis) {
        idleCouriers.values().removeIf(lastSeen -> nowMillis - lastSeen > supplyWindowMillis);
        demandByMinute.keySet().removeIf(bucket -> (bucket + 1) * BUCKET_MILLIS <= nowMillis - demandWindowMillis);
    }
}
