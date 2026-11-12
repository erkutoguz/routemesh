package com.routemesh.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Gerçek sunucuda metrik ucu kimliksiz açıktır, uygulama etiketini taşır; geri kalan yüzey korunur. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.grpc.server.port=0", "routemesh.payment.reconciliation.scheduler-enabled=false"})
@AutoConfigureMetrics
@Import({TestcontainersConfiguration.class, TestPspConfiguration.class})
class ObservabilityTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void prometheusMetricsAreServedWithoutAuthenticationAndCarryTheApplicationTag() throws Exception {
        get("/actuator/health");

        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes").contains("application=\"payment-service\"");
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isIn(401, 404);
        assertThat(get("/actuator/metrics").statusCode()).isIn(401, 404);
        assertThat(get("/actuator/beans").statusCode()).isIn(401, 404);
    }

    @Test
    void theOpsEndpointsAndEverythingElseStayClosedWhileMetricsAreOpen() throws Exception {
        assertThat(get("/api/v1/ops/payments/reconciliation").statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/payments/anything").statusCode()).isEqualTo(401);
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
