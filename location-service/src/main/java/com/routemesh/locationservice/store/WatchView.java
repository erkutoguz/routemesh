package com.routemesh.locationservice.store;

/** Etkin bir izlemenin okunabilir hali (canlı takip yetkisi ve hedef için). */
public record WatchView(String shipmentId, String courierId, String kind, double latitude, double longitude,
                        String merchantId) {
}
