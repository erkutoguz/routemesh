package com.routemesh.shipmentservice.domain;

import java.time.Instant;
import java.util.UUID;

public record PodFile(
        UUID id,
        UUID shipmentId,
        Kind kind,
        String objectKey,
        String sha256,
        long sizeBytes,
        String contentType,
        UUID uploadedBy,
        Instant uploadedAt) {

    public enum Kind {
        PHOTO,
        SIGNATURE
    }
}
