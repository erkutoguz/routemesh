package com.routemesh.shipmentservice.service;

public class PodTooFarException extends ShipmentException {

    private final double distanceMeters;

    public PodTooFarException(double distanceMeters) {
        super(Reason.POD_TOO_FAR, "reported location is " + distanceMeters + " m from the delivery location, at most "
                + ProofOfDeliveryService.MAX_DISTANCE_METERS + " m is allowed");
        this.distanceMeters = distanceMeters;
    }

    public double getDistanceMeters() {
        return distanceMeters;
    }
}
