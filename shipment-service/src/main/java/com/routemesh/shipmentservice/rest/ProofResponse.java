package com.routemesh.shipmentservice.rest;

import java.time.Instant;
import java.util.UUID;

import com.routemesh.shipmentservice.domain.PodFile;
import com.routemesh.shipmentservice.domain.ProofOfDelivery;

record ProofResponse(
        UUID shipmentId,
        UUID courierId,
        Instant recordedAt,
        ShipmentResponse.Location deliveredLocation,
        double distanceMeters,
        Double accuracyMeters,
        String recipientName,
        boolean otpVerified,
        PodFileResponse photo,
        PodFileResponse signature) {

    static ProofResponse from(ProofOfDelivery proof, PodFile photo, PodFile signature) {
        return new ProofResponse(proof.shipmentId(), proof.courierId(), proof.recordedAt(),
                new ShipmentResponse.Location(proof.deliveredLatitude(), proof.deliveredLongitude()),
                proof.distanceMeters(), proof.accuracyMeters(), proof.recipientName(), proof.otpVerified(),
                PodFileResponse.from(photo), signature == null ? null : PodFileResponse.from(signature));
    }
}
