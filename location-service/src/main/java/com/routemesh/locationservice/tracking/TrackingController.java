package com.routemesh.locationservice.tracking;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/tracking")
@PreAuthorize("hasRole('MERCHANT')")
class TrackingController {

    private final TrackingStreamService streams;

    TrackingController(TrackingStreamService streams) {
        this.streams = streams;
    }

    @GetMapping("/{shipmentId}/stream")
    ResponseEntity<SseEmitter> stream(@PathVariable UUID shipmentId, @AuthenticationPrincipal Jwt jwt) {
        SseEmitter emitter = streams.open(shipmentId.toString(), jwt.getSubject(), jwt.getExpiresAt());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                // nginx gibi ara katmanlar akışı tamponlamasın
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
