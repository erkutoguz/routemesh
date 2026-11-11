package com.routemesh.shipmentservice.domain;

import java.time.Instant;
import java.util.UUID;

public record ProofOfDelivery(
        UUID shipmentId,
        UUID courierId,
        double deliveredLatitude,
        double deliveredLongitude,
        double distanceMeters,
        Double accuracyMeters,
        String recipientName,
        UUID photoFileId,
        UUID signatureFileId,
        boolean otpVerified,
        Instant recordedAt) {
}
