package com.routemesh.platform.security.test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.routemesh.platform.security.AudienceValidator;

import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class TestJwts {

    public static final String ISSUER = "http://localhost:8180/realms/routemesh";
    public static final String AUDIENCE = "routemesh-api";

    private static final RSAKey KEY = generate();

    private TestJwts() {
    }

    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER), new AudienceValidator(AUDIENCE)));
            return decoder;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String token(String subject, String... roles) {
        return token(subject, 300, roles);
    }

    public static String token(String subject, long ttlSeconds, String... roles) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now.minusSeconds(1)))
                    .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                    .claim("realm_access", Map.of("roles", List.of(roles)))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ClientInterceptor bearer(String token) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return MetadataUtils.newAttachHeadersInterceptor(metadata);
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
