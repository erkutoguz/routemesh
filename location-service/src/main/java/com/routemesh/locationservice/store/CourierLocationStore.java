package com.routemesh.locationservice.store;

import java.util.List;

public interface CourierLocationStore {

    boolean ingest(String courierId, long sessionEpoch, long sequenceNumber, double latitude, double longitude);

    List<NearbyCourier> findNearest(double latitude, double longitude, double radiusMeters, int limit);

    long evictStale();
}
