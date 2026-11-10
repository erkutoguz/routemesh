package com.routemesh.locationservice.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.routemesh.locationservice.config.LocationProperties;
import com.routemesh.locationservice.kafka.ApproachPublisher;
import com.routemesh.locationservice.kafka.TelemetryPublisher;
import com.routemesh.locationservice.store.CourierLocationStore;
import com.routemesh.locationservice.store.IngestResult;
import com.routemesh.locationservice.store.NearbyCourier;

@Service
public class CourierLocationService {

    // Redis GEOADD enlemi yalnızca bu aralıkta kabul eder (Web Mercator sınırı)
    static final double MAX_LATITUDE = 85.05112878;
    static final double MAX_LONGITUDE = 180.0;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final CourierLocationStore store;
    private final LocationProperties properties;
    private final ApproachPublisher approachPublisher;
    private final TelemetryPublisher telemetryPublisher;

    public CourierLocationService(CourierLocationStore store, LocationProperties properties,
                                  ApproachPublisher approachPublisher, TelemetryPublisher telemetryPublisher) {
        this.store = store;
        this.properties = properties;
        this.approachPublisher = approachPublisher;
        this.telemetryPublisher = telemetryPublisher;
    }

    /**
     * @return true: paket kabul edildi, false: eski paket olduğu için atıldı
     * @throws InvalidRequestException mesaj geçersizse
     */
    public boolean ingest(String courierId, long sessionEpoch, long sequenceNumber,
                          double latitude, double longitude) {
        String normalizedId = normalizeCourierId(courierId);
        if (sessionEpoch < 0) {
            throw new InvalidRequestException("session_epoch must not be negative");
        }
        if (sequenceNumber < 0) {
            throw new InvalidRequestException("sequence_number must not be negative");
        }
        validateCoordinates(latitude, longitude);

        IngestResult result = store.ingest(normalizedId, sessionEpoch, sequenceNumber, latitude, longitude);
        if (result.accepted()) {
            telemetryPublisher.offer(normalizedId, sessionEpoch, sequenceNumber, latitude, longitude);
            if (result.hit() != null) {
                approachPublisher.publish(result.hit(), latitude, longitude);
            }
        }
        return result.accepted();
    }

    /**
     * @throws InvalidRequestException istek geçersizse
     */
    public List<NearbyCourier> findNearest(double latitude, double longitude,
                                           double radiusMeters, int limit) {
        validateCoordinates(latitude, longitude);
        if (!(radiusMeters > 0 && radiusMeters <= properties.maxRadiusMeters())) {
            throw new InvalidRequestException(
                    "radius_meters must be greater than 0 and at most " + properties.maxRadiusMeters());
        }
        if (limit < 0) {
            throw new InvalidRequestException("limit must not be negative");
        }
        int effectiveLimit = limit == 0
                ? properties.defaultLimit()
                : Math.min(limit, properties.maxLimit());

        return store.findNearest(latitude, longitude, radiusMeters, effectiveLimit);
    }

    // Aynı kurye büyük/küçük harf farkıyla iki ayrı kayıt olmasın diye küçük harfe çevrilir
    private static String normalizeCourierId(String courierId) {
        if (courierId == null || !UUID_PATTERN.matcher(courierId).matches()) {
            throw new InvalidRequestException("courier_id must be a valid UUID");
        }
        return courierId.toLowerCase(Locale.ROOT);
    }

    // NaN ve sonsuz değerler karşılaştırmayı geçemesin diye koşul "geçerli aralıkta mı" şeklinde yazılır
    private static void validateCoordinates(double latitude, double longitude) {
        if (!(latitude >= -MAX_LATITUDE && latitude <= MAX_LATITUDE)) {
            throw new InvalidRequestException(
                    "latitude must be between -" + MAX_LATITUDE + " and " + MAX_LATITUDE);
        }
        if (!(longitude >= -MAX_LONGITUDE && longitude <= MAX_LONGITUDE)) {
            throw new InvalidRequestException(
                    "longitude must be between -" + MAX_LONGITUDE + " and " + MAX_LONGITUDE);
        }
    }
}
