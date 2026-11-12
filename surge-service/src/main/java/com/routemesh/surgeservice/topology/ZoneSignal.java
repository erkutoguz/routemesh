package com.routemesh.surgeservice.topology;

/**
 * Bir bölgeye ait olay. {@code SEEN}: IDLE kurye bölgede görüldü, {@code GONE}: kurye artık IDLE değil (bölgenin
 * arzından çıkar), {@code DEMAND}: bölgeye yeni gönderi. {@code timestamp}: kaydın Kafka zamanı (epoch ms).
 */
public record ZoneSignal(Type type, String id, long timestamp) {

    public enum Type {
        SEEN,
        GONE,
        DEMAND
    }
}
