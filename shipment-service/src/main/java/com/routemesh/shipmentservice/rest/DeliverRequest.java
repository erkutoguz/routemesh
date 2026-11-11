package com.routemesh.shipmentservice.rest;

import java.util.UUID;

record DeliverRequest(
        UUID photoFileId,
        UUID signatureFileId,
        Double latitude,
        Double longitude,
        Double accuracyMeters,
        String recipientName,
        String otp) {
}
