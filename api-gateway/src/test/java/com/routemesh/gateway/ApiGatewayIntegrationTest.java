package com.routemesh.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayIntegrationTest {

    private static final String ISSUER = "http://issuer.test/realms/routemesh";
    private static final AtomicReference<String> upstreamAuthorization = new AtomicReference<>();
    private static final AtomicReference<String> upstreamPath = new AtomicReference<>();

    private static final RSAKey SIGNING_KEY = generateKey("signing");
    private static final RSAKey FOREIGN_KEY = generateKey("signing");
    private static final HttpServer UPSTREAM = startUpstream();
    private static final AtomicReference<String> workflowCall = new AtomicReference<>();
    private static final HttpServer WORKFLOW_UPSTREAM = startWorkflowUpstream();

    private static final CountDownLatch RELEASE_SECOND_EVENT = new CountDownLatch(1);
    private static final AtomicReference<String> trackingCall = new AtomicReference<>();
    private static final HttpServer TRACKING_UPSTREAM = startTrackingUpstream();
    private static final AtomicReference<String> ledgerCall = new AtomicReference<>();
    private static final HttpServer LEDGER_UPSTREAM = startLedgerUpstream();

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + UPSTREAM.getAddress().getPort();
        registry.add("routemesh.security.issuer", () -> ISSUER);
        registry.add("routemesh.security.jwk-set-uri", () -> base + "/jwks");
        registry.add("routemesh.upstreams.shipment", () -> base);
        registry.add("routemesh.upstreams.workflow",
                () -> "http://localhost:" + WORKFLOW_UPSTREAM.getAddress().getPort());
        registry.add("routemesh.upstreams.tracking",
                () -> "http://localhost:" + TRACKING_UPSTREAM.getAddress().getPort());
        registry.add("routemesh.upstreams.ledger",
                () -> "http://localhost:" + LEDGER_UPSTREAM.getAddress().getPort());
    }

    @AfterAll
    static void stopUpstream() {
        RELEASE_SECOND_EVENT.countDown();
        UPSTREAM.stop(0);
        WORKFLOW_UPSTREAM.stop(0);
        TRACKING_UPSTREAM.stop(0);
        LEDGER_UPSTREAM.stop(0);
    }

    @BeforeEach
    void reset() {
        upstreamAuthorization.set(null);
        upstreamPath.set(null);
        workflowCall.set(null);
    }

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        assertThat(get("/api/v1/shipments/1", null).statusCode()).isEqualTo(401);
        assertThat(upstreamPath.get()).isNull();
    }

    @Test
    void validTokenIsForwardedUnchangedToUpstream() throws Exception {
        String token = token(SIGNING_KEY, ISSUER, List.of("routemesh-api"), List.of("MERCHANT"), 300);

        HttpResponse<String> response = get("/api/v1/shipments/1", token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(upstreamPath.get()).isEqualTo("/api/v1/shipments/1");
        assertThat(upstreamAuthorization.get()).isEqualTo("Bearer " + token);
    }

    @Test
    void postToShipmentsGoesToWorkflowServiceWhileOtherCallsGoToShipmentService() throws Exception {
        String token = token(SIGNING_KEY, ISSUER, List.of("routemesh-api"), List.of("MERCHANT"), 300);

        assertThat(send("POST", "/api/v1/shipments", token).statusCode()).isEqualTo(200);
        assertThat(workflowCall.get()).isEqualTo("POST /api/v1/shipments Bearer " + token);
        assertThat(upstreamPath.get()).isNull();

        assertThat(get("/api/v1/shipments/1", token).statusCode()).isEqualTo(200);
        assertThat(upstreamPath.get()).isEqualTo("/api/v1/shipments/1");
        assertThat(workflowCall.get()).isEqualTo("POST /api/v1/shipments Bearer " + token);

        workflowCall.set(null);
        assertThat(send("POST", "/api/v1/shipments", null).statusCode()).isEqualTo(401);
        assertThat(workflowCall.get()).isNull();
    }

    @Test
    void warehousePathsGoToShipmentService() throws Exception {
        String token = token(SIGNING_KEY, ISSUER, List.of("routemesh-api"), List.of("WAREHOUSE"), 300);

        assertThat(send("POST", "/api/v1/warehouse/shipments/receive", token).statusCode()).isEqualTo(200);
        assertThat(upstreamPath.get()).isEqualTo("/api/v1/warehouse/shipments/receive");
        assertThat(upstreamAuthorization.get()).isEqualTo("Bearer " + token);
        assertThat(workflowCall.get()).isNull();

        upstreamPath.set(null);
        assertThat(send("POST", "/api/v1/warehouse/shipments/1/ready", null).statusCode()).isEqualTo(401);
        assertThat(upstreamPath.get()).isNull();
    }

    @Test
    void tokenWithWrongAudienceIsRejected() throws Exception {
        String token = token(SIGNING_KEY, ISSUER, List.of("account"), List.of("MERCHANT"), 300);
        assertThat(get("/api/v1/shipments/1", token).statusCode()).isEqualTo(401);
        assertThat(upstreamPath.get()).isNull();
    }

    @Test
    void tokenWithWrongIssuerIsRejected() throws Exception {
        String token = token(SIGNING_KEY, "http://evil.test/realms/routemesh", List.of("routemesh-api"),
                List.of("MERCHANT"), 300);
        assertThat(get("/api/v1/shipments/1", token).statusCode()).isEqualTo(401);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = token(SIGNING_KEY, ISSUER, List.of("routemesh-api"), List.of("MERCHANT"), -300);
        assertThat(get("/api/v1/shipments/1", token).statusCode()).isEqualTo(401);
    }

    @Test
    void tokenSignedWithUnknownKeyIsRejected() throws Exception {
        String token = token(FOREIGN_KEY, ISSUER, List.of("routemesh-api"), List.of("OPS"), 300);
        assertThat(get("/api/v1/shipments/1", token).statusCode()).isEqualTo(401);
    }

    @Test
    void opsPathRequiresOpsRole() throws Exception {
        String merchant = token(SIGNING_KEY, ISSUER, List.of("routemesh-api"), List.of("MERCHANT"), 300);
        String ops = token(SIGNING_KEY, ISSUER, List.of("routemesh-api"), List.of("OPS"), 300);

        assertThat(get("/api/v1/ops/shipments", merchant).statusCode()).isEqualTo(403);
        assertThat(upstreamPath.get()).isNull();
        assertThat(get("/api/v1/ops/shipments", ops).statusCode()).isEqualTo(200);
        assertThat(upstreamPath.get()).isEqualTo("/api/v1/ops/shipments");
    }

    @Test
    void trackingStreamIsForwardedAsItIsProducedWithoutBuffering() throws Exception {
        String token = token(SIGNING_KEY, ISSUER, List.of("routemesh-api"), List.of("MERCHANT"), 300);
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/v1/tracking/abc/stream"))
                .header("Accept", "text/event-stream").header("Authorization", "Bearer " + token).build();

        HttpResponse<java.util.stream.Stream<String>> response =
                client.send(request, HttpResponse.BodyHandlers.ofLines());
        java.util.Iterator<String> lines = response.body().iterator();

        // Upstream ikinci olayı yayınlamadı; ilk olay yine de istemciye ulaşmalı
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("x-accel-buffering")).contains("no");
        assertThat(java.util.concurrent.CompletableFuture.supplyAsync(lines::next)
                .get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("data:first");
        assertThat(RELEASE_SECOND_EVENT.getCount()).isEqualTo(1);

        RELEASE_SECOND_EVENT.countDown();
        lines.next(); // boş satır
        assertThat(java.util.concurrent.CompletableFuture.supplyAsync(lines::next)
                .get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("data:second");
        assertThat(trackingCall.get()).isEqualTo("/api/v1/tracking/abc/stream Bearer " + token);
        response.body().close();
    }

    @Test
    void walletPathsReachTheLedgerServiceWithTheCallersTokenUntouched() throws Exception {
        for (String role : new String[] {"MERCHANT", "COURIER", "OPS"}) {
            String token = token(SIGNING_KEY, ISSUER, List.of("routemesh-api"), List.of(role), 300);
            ledgerCall.set(null);

            // Rol kararı ledger-service'in: gateway yalnızca kimliği doğrular ve token'ı aynen iletir
            assertThat(get("/api/v1/wallets/me", token).statusCode()).isEqualTo(200);
            assertThat(ledgerCall.get()).isEqualTo("/api/v1/wallets/me Bearer " + token);

            ledgerCall.set(null);
            assertThat(get("/api/v1/wallets/me/transactions?limit=2&cursor=17", token).statusCode()).isEqualTo(200);
            assertThat(ledgerCall.get()).isEqualTo("/api/v1/wallets/me/transactions?limit=2&cursor=17 Bearer " + token);
        }
        assertThat(upstreamPath.get()).isNull();
        assertThat(workflowCall.get()).isNull();
    }

    @Test
    void walletPathsWithoutAValidTokenNeverReachTheLedgerService() throws Exception {
        ledgerCall.set(null);
        String wrongAudience = token(SIGNING_KEY, ISSUER, List.of("account"), List.of("MERCHANT"), 300);
        String expired = token(SIGNING_KEY, ISSUER, List.of("routemesh-api"), List.of("MERCHANT"), -300);
        String foreign = token(FOREIGN_KEY, ISSUER, List.of("routemesh-api"), List.of("MERCHANT"), 300);

        assertThat(get("/api/v1/wallets/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/wallets/me/transactions", wrongAudience).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/wallets/me", expired).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/wallets/me", foreign).statusCode()).isEqualTo(401);
        assertThat(ledgerCall.get()).isNull();
    }

    @Test
    void trackingStreamWithoutTokenIsRejectedAtTheGateway() throws Exception {
        trackingCall.set(null);

        assertThat(get("/api/v1/tracking/abc/stream", null).statusCode()).isEqualTo(401);
        assertThat(trackingCall.get()).isNull();
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send("GET", path, token);
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{}")
                        : HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(RSAKey key, String issuer, List<String> audience, List<String> roles,
            long ttlSeconds) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now.minusSeconds(600)))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("realm_access", Map.of("roles", roles))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey generateKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startWorkflowUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                workflowCall.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                        + exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startLedgerUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                ledgerCall.set(exchange.getRequestURI().getPath() + (query == null ? "" : "?" + query) + " "
                        + exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startTrackingUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                trackingCall.set(exchange.getRequestURI().getPath() + " "
                        + exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().add("Cache-Control", "no-cache");
                exchange.getResponseHeaders().add("X-Accel-Buffering", "no");
                exchange.sendResponseHeaders(200, 0); // parçalı yanıt
                try {
                    exchange.getResponseBody().write("data:first\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    RELEASE_SECOND_EVENT.await(30, TimeUnit.SECONDS);
                    exchange.getResponseBody().write("data:second\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                } catch (IOException | InterruptedException ignored) {
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/api", exchange -> {
                upstreamAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                upstreamPath.set(exchange.getRequestURI().getPath());
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try {
                    exchange.getResponseBody().write(body);
                } catch (IOException ignored) {
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
