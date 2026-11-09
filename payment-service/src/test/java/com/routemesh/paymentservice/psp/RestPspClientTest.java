package com.routemesh.paymentservice.psp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.routemesh.paymentservice.config.PaymentProperties;
import com.sun.net.httpserver.HttpServer;

class RestPspClientTest {

    private record Received(String method, String path, String idempotencyKey, String body) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"id\":\"psp_1\",\"checkout_url\":\"http://x/checkout\"}";
    private HttpServer server;
    private RestPspClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"), body));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        client = clientFor("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createPayment_postsTheRequestWithTheIdempotencyKey_andParsesTheResponse() {
        PspClient.CreatedPayment created = client.createPayment("create:k1", 1001, "TRY", "http://hook", "ref-1");

        assertThat(created.id()).isEqualTo("psp_1");
        assertThat(created.checkoutUrl()).isEqualTo("http://x/checkout");
        Received request = received.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/payments");
        assertThat(request.idempotencyKey()).isEqualTo("create:k1");
        assertThat(request.body()).contains("\"amount_minor\":1001").contains("\"currency\":\"TRY\"")
                .contains("\"callback_url\":\"http://hook\"").contains("\"reference\":\"ref-1\"");
    }

    @Test
    void capture_void_andRefund_hitTheRightPathsWithTheirKeys() {
        responseBody = "{\"id\":\"psp_1\"}";

        client.capture("psp_1", "key-capture");
        client.voidPayment("psp_1", "key-void");
        responseBody = "{\"id\":\"re_9\"}";
        String refundId = client.refund("psp_1", "key-refund", 400);

        assertThat(refundId).isEqualTo("re_9");
        assertThat(received).extracting(Received::path).containsExactly("/v1/payments/psp_1/capture",
                "/v1/payments/psp_1/void", "/v1/payments/psp_1/refunds");
        assertThat(received).extracting(Received::idempotencyKey).containsExactly("key-capture", "key-void",
                "key-refund");
        assertThat(received.get(2).body()).contains("\"amount_minor\":400");
    }

    @Test
    void definitiveClientErrors_areRejections() {
        for (int code : new int[] {400, 404, 409, 422}) {
            status = code;
            responseBody = "{\"error\":\"nope\"}";

            assertThatThrownBy(() -> client.capture("psp_1", "k"))
                    .as("status " + code).isInstanceOf(PspRejectedException.class);
        }
    }

    @Test
    void serverErrorsAndThrottling_areTransient() {
        for (int code : new int[] {408, 429, 500, 502, 503}) {
            status = code;
            responseBody = "{\"error\":\"later\"}";

            assertThatThrownBy(() -> client.capture("psp_1", "k"))
                    .as("status " + code).isInstanceOf(PspUnavailableException.class);
        }
    }

    @Test
    void emptyResponse_isTransient() {
        status = 200;
        responseBody = "";

        assertThatThrownBy(() -> client.capture("psp_1", "k")).isInstanceOf(PspUnavailableException.class);
    }

    @Test
    void connectionFailure_isTransient() {
        RestPspClient unreachable = clientFor("http://127.0.0.1:1");

        assertThatThrownBy(() -> unreachable.capture("psp_1", "k")).isInstanceOf(PspUnavailableException.class);
    }

    private static RestPspClient clientFor(String baseUrl) {
        PaymentProperties properties = new PaymentProperties(baseUrl, "http://hook", List.of("s"),
                Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(1), Duration.ofSeconds(30), 10);
        return new RestPspClient(RestClient.builder(), properties);
    }
}
