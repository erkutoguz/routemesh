package com.routemesh.locationservice.grpc;

import java.time.Instant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.grpc.Status;

final class CourierTokenGuard {

    private CourierTokenGuard() {
    }

    static void verify(String courierId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw Status.UNAUTHENTICATED.withDescription("missing token").asRuntimeException();
        }
        Instant expiresAt = token.getToken().getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw Status.UNAUTHENTICATED
                    .withDescription("token expired, reconnect with a new token and a new session_epoch")
                    .asRuntimeException();
        }
        if (!token.getName().equals(courierId)) {
            throw Status.PERMISSION_DENIED
                    .withDescription("courier_id does not match the token subject")
                    .asRuntimeException();
        }
    }
}
