package com.routemesh.shipmentservice.rest;

import java.io.IOException;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.routemesh.shipmentservice.domain.DeliverCommand;
import com.routemesh.shipmentservice.domain.PodFile;
import com.routemesh.shipmentservice.domain.ProofOfDelivery;
import com.routemesh.shipmentservice.service.ProofOfDeliveryService;
import com.routemesh.shipmentservice.service.ProofOfDeliveryService.FileContent;
import com.routemesh.shipmentservice.service.ShipmentException;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;

@RestController
@RequestMapping("/api/v1/shipments/{id}")
class ProofOfDeliveryController {

    private final ProofOfDeliveryService service;

    ProofOfDeliveryController(ProofOfDeliveryService service) {
        this.service = service;
    }

    @PostMapping(path = "/pod/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('COURIER')")
    ResponseEntity<PodFileResponse> upload(@PathVariable UUID id, @RequestParam PodFile.Kind kind,
            @RequestParam("file") MultipartFile file, Authentication authentication) throws IOException {
        PodFile stored = service.upload(id, courierId(authentication), kind, file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(PodFileResponse.from(stored));
    }

    @PostMapping("/deliver")
    @PreAuthorize("hasRole('COURIER')")
    ShipmentResponse deliver(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody DeliverRequest request, Authentication authentication) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new ShipmentException(Reason.INVALID_REQUEST, "Idempotency-Key must be 1 to 255 characters");
        }
        if (request.latitude() == null || request.longitude() == null) {
            throw new ShipmentException(Reason.INVALID_REQUEST, "latitude and longitude are required");
        }
        return ShipmentResponse.from(service.deliver(new DeliverCommand(id, courierId(authentication), idempotencyKey,
                request.photoFileId(), request.signatureFileId(), request.latitude(), request.longitude(),
                request.accuracyMeters(), request.recipientName(), request.otp())).shipment());
    }

    @GetMapping("/pod")
    @PreAuthorize("hasAnyRole('MERCHANT', 'COURIER', 'OPS')")
    ProofResponse proof(@PathVariable UUID id, Authentication authentication) {
        CallerActor caller = CallerActor.from(authentication);
        ProofOfDelivery proof = service.getProof(id, caller.canAccess());
        return ProofResponse.from(proof, service.getFileMetadata(id, proof.photoFileId()),
                proof.signatureFileId() == null ? null : service.getFileMetadata(id, proof.signatureFileId()));
    }

    @GetMapping("/pod/files/{fileId}")
    @PreAuthorize("hasAnyRole('MERCHANT', 'COURIER', 'OPS')")
    ResponseEntity<InputStreamResource> file(@PathVariable UUID id, @PathVariable UUID fileId,
            Authentication authentication) {
        CallerActor caller = CallerActor.from(authentication);
        FileContent content = service.openFile(id, fileId, caller.actor(), caller.canAccess());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.file().contentType()))
                .contentLength(content.file().sizeBytes())
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new InputStreamResource(content.stream()));
    }

    private static UUID courierId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ShipmentException(Reason.NOT_FOUND, "shipment does not exist");
        }
    }
}
