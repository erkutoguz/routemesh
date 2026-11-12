package com.routemesh.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

/**
 * Gerçek sunucuda (platform-security zinciriyle) metrik ucu ve HTTP izleme yayılımı. Test JWT yapılandırması
 * bilerek kullanılmaz: o, Boot'un varsayılan zincirini devreye alıp üretim davranışından ayrışır.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.grpc.server.port=0",
        "routemesh.ledger.integrity.scheduler-enabled=false",
        "management.tracing.export.enabled=true",
        "management.tracing.export.otlp.enabled=false",
        "management.opentelemetry.tracing.export.schedule-delay=50ms"})
@AutoConfigureMetrics
@Import({TestcontainersConfiguration.class, ObservabilityTest.SpanCollector.class})
class ObservabilityTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class SpanCollector {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private InMemorySpanExporter spans;

    @Value("${management.endpoints.web.exposure.include}")
    private String exposure;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clear() {
        spans.reset();
    }

    @Test
    void prometheusMetricsAreServedWithoutAuthenticationAndCarryTheApplicationTag() throws Exception {
        get("/api/v1/wallets/me", null);
        get("/actuator/health", null);

        HttpResponse<String> response = get("/actuator/prometheus", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes").contains("application=\"ledger-service\"")
                .contains("http_server_requests_seconds_bucket")
                // Sağlık denetimi ve scrape gözlemlenmez: iz ve HTTP metriği üretmez
                .doesNotContain("uri=\"/actuator/health\"").doesNotContain("uri=\"/actuator/prometheus\"");
    }

    @Test
    void onlyHealthInfoAndPrometheusAreExposedAndTheRestOfTheServiceStaysProtected() throws Exception {
        assertThat(exposure).isEqualTo("health,info,prometheus");

        assertThat(get("/actuator/env", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/metrics", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/wallets/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/ops/ledger/integrity", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void anIncomingHttpTraceContinuesInTheServerSpan() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/v1/wallets/me"))
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(spans.getFinishedSpanItems())
                .anySatisfy(span -> {
                    assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
                    assertThat(span.getTraceId()).isEqualTo(traceId);
                    assertThat(span.getParentSpanId()).isEqualTo("00f067aa0ba902b7");
                }));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
