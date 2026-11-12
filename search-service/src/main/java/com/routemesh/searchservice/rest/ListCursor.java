package com.routemesh.searchservice.rest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/** Opak imleç: son satırın (oluşturulma zamanı mikrosaniye, kimlik) çifti. */
record ListCursor(Instant createdAt, UUID id) {

    String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    static ListCursor decode(String value) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("malformed cursor");
            }
            return new ListCursor(Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS),
                    UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cursor is not valid", e);
        }
    }
}
