package com.routemesh.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * Upstream kapalıyken ya da yavaşken gateway 500 yerine Problem Details (503/504) döner; istek gövdesi sınırı 413 verir.
 * shipment upstream'i 3 sn uyuyan bir sunucudur ve okuma zaman aşımı 1 sn'dir; ledger upstream'inde kimse dinlemez.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "spring.http.clients.read-timeout=1s"})
class GatewayUpstreamFailureTest {

    private static final String ISSUER = "http://issuer.test/realms/routemesh";
    private static final RSAKey KEY = generateKey();
    private static final HttpServer JWKS_AND_SLOW = startServer();
    private static final int DEAD_PORT = freePort();

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + JWKS_AND_SLOW.getAddress().getPort();
        registry.add("routemesh.security.issuer", () -> ISSUER);
        registry.add("routemesh.security.jwk-set-uri", () -> base + "/jwks");
        registry.add("routemesh.upstreams.shipment", () -> base);
        registry.add("routemesh.upstreams.ledger", () -> "http://localhost:" + DEAD_PORT);
    }

    @AfterAll
    static void stop() {
        JWKS_AND_SLOW.stop(0);
    }

    @Test
    void aSlowUpstreamAnswers504WithProblemDetails() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/shipments/1", token(), "");

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(response.body()).contains("\"status\":504");
    }

    @Test
    void anUpstreamThatRefusesConnectionsAnswers503WithProblemDetails() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/wallets/me", token(), "");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(response.body()).contains("\"status\":503");
    }

    @Test
    void aBodyOverOneMegabyteIsRejectedWith413BeforeAuthentication() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/shipments/1/transitions", null,
                "x".repeat(1024 * 1024 + 1));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(response.body()).contains("\"status\":413");
    }

    @Test
    void theProofOfDeliveryUploadPathAllowsUpToSixMegabytes() throws Exception {
        String path = "/api/v1/shipments/1/pod/files";

        assertThat(send("POST", path, token(), "x".repeat(2 * 1024 * 1024)).statusCode()).isNotEqualTo(413);
        assertThat(send("POST", path, token(), "x".repeat(6 * 1024 * 1024 + 1)).statusCode()).isEqualTo(413);
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString(body)
                        : HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience(List.of("routemesh-api"))
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("realm_access", Map.of("roles", List.of("MERCHANT")))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("signing").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/api", exchange -> {
                try {
                    Thread.sleep(3000);
                    exchange.sendResponseHeaders(200, -1);
                } catch (InterruptedException | IOException ignored) {
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
