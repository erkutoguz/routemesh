package com.routemesh.surgeservice.zone;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.routemesh.contracts.common.LatLong;
import com.routemesh.contracts.pricing.LocateZoneRequest;
import com.routemesh.contracts.pricing.LocateZoneResponse;
import com.routemesh.contracts.pricing.PricingServiceGrpc;
import com.routemesh.surgeservice.config.SurgeProperties;

import io.grpc.StatusRuntimeException;

/**
 * Bölgeyi pricing-service'ten çözer (tek doğruluk kaynağı). Sonuç, koordinatın {@code zoneCellDegrees} ile
 * yuvarlanmış hücresine göre önbelleğe alınır; hücredeki tüm noktalar hücre merkezinin bölgesini alır (bölge
 * sınırlarında ~275 m yaklaşıklık). Bölge dışı sonuç da önbelleğe alınır, hata alınmaz.
 */
public class PricingZoneLocator implements ZoneLocator {

    private static final Logger log = LoggerFactory.getLogger(PricingZoneLocator.class);
    private static final int MAX_CACHED_CELLS = 50_000;

    private record Cell(long latitudeIndex, long longitudeIndex) {
    }

    private record Cached(Optional<String> zone, long expiresAtMillis) {
    }

    private final PricingServiceGrpc.PricingServiceBlockingStub pricing;
    private final double cellDegrees;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Cell, Cached> cache = new ConcurrentHashMap<>();

    public PricingZoneLocator(PricingServiceGrpc.PricingServiceBlockingStub pricing, SurgeProperties properties,
                              LongSupplier clock) {
        this.pricing = pricing;
        this.cellDegrees = properties.zoneCellDegrees();
        this.ttlMillis = properties.zoneCacheTtl().toMillis();
        this.clock = clock;
    }

    @Override
    public Optional<String> locate(double latitude, double longitude) {
        Cell cell = new Cell(Math.round(latitude / cellDegrees), Math.round(longitude / cellDegrees));
        long now = clock.getAsLong();
        Cached cached = cache.get(cell);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.zone();
        }
        try {
            LocateZoneResponse response = pricing.locateZone(LocateZoneRequest.newBuilder()
                    .setLocation(LatLong.newBuilder().setLatitude(cell.latitudeIndex() * cellDegrees)
                            .setLongitude(cell.longitudeIndex() * cellDegrees))
                    .build());
            Optional<String> zone = response.hasZoneCode() ? Optional.of(response.getZoneCode()) : Optional.empty();
            if (cache.size() >= MAX_CACHED_CELLS) {
                cache.clear();
            }
            cache.put(cell, new Cached(zone, now + ttlMillis));
            return zone;
        } catch (StatusRuntimeException e) {
            log.warn("Zone lookup failed for cell {}: {}", cell, e.getStatus());
            return Optional.empty();
        }
    }
}
