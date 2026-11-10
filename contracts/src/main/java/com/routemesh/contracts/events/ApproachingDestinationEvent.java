package com.routemesh.contracts.events;

import java.util.UUID;

/**
 * Kurye izlenen hedefe (depo ya da teslimat noktası) yaklaştı. {@code location.events} topic'ine,
 * {@code shipmentId} anahtarıyla yazılır. {@code kind}: PICKUP (depo) ya da DELIVERY (teslimat noktası).
 */
public record ApproachingDestinationEvent(
        UUID shipmentId,
        UUID courierId,
        String kind,
        double distanceMeters,
        double latitude,
        double longitude) {

    public static final String TYPE = "ApproachingDestination";
    public static final int VERSION = 1;
}
