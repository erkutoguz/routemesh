package com.routemesh.locationservice.tracking;

final class Haversine {

    // Redis GEODIST ve ingest.lua ile aynı yarıçap, mesafeler birbirini tutsun
    private static final double EARTH_RADIUS_METERS = 6372797.560856;

    private Haversine() {
    }

    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dPhi / 2), 2) + Math.cos(phi1) * Math.cos(phi2) * Math.pow(Math.sin(dLambda / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
