package com.routemesh.platform.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class RealmRoleConverterTest {

    private final RealmRoleConverter converter = new RealmRoleConverter();

    @Test
    void mapsRealmRolesToRolePrefixedAuthorities() {
        Jwt jwt = jwtWith(Map.of("realm_access", Map.of("roles", List.of("OPS", "MERCHANT"))));
        assertThat(converter.convert(jwt)).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_OPS", "ROLE_MERCHANT");
    }

    @Test
    void returnsNoAuthoritiesWithoutRealmAccess() {
        assertThat(converter.convert(jwtWith(Map.of("scope", "x")))).isEmpty();
    }

    @Test
    void ignoresMalformedRealmAccess() {
        assertThat(converter.convert(jwtWith(Map.of("realm_access", Map.of("roles", "OPS"))))).isEmpty();
    }

    private Jwt jwtWith(Map<String, Object> claims) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("u").claims(c -> c.putAll(claims))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
