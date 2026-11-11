package com.routemesh.shipmentservice.rest;

import java.util.UUID;

import com.routemesh.shipmentservice.domain.PodFile;

record PodFileResponse(UUID fileId, String kind, String sha256, long sizeBytes, String contentType) {

    static PodFileResponse from(PodFile file) {
        return new PodFileResponse(file.id(), file.kind().name(), file.sha256(), file.sizeBytes(),
                file.contentType());
    }
}
