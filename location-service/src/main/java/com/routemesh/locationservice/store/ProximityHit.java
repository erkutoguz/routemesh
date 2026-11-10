package com.routemesh.locationservice.store;

public record ProximityHit(String courierId, String shipmentId, String kind, double distanceMeters) {
}
