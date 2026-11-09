package com.routemesh.platform.security;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("routemesh-api");

    @Test
    void acceptsTokenWithRequiredAudience() {
        assertThat(validator.validate(jwt(List.of("account", "routemesh-api"))).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenWithOtherAudience() {
        assertThat(validator.validate(jwt(List.of("account"))).hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithoutAudience() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    private Jwt jwt(List<String> audience) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("u").audience(audience)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
