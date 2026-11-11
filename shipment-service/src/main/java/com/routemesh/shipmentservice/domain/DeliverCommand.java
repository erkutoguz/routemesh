package com.routemesh.shipmentservice.domain;

import java.util.UUID;

public record DeliverCommand(
        UUID shipmentId,
        UUID courierId,
        String transitionKey,
        UUID photoFileId,
        UUID signatureFileId,
        double latitude,
        double longitude,
        Double accuracyMeters,
        String recipientName,
        String otp) {
}
