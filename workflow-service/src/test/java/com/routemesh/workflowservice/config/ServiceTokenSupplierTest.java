package com.routemesh.workflowservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class ServiceTokenSupplierTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastForm = new AtomicReference<>();
    private volatile String response = "{\"access_token\":\"t1\",\"expires_in\":300}";
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesClientCredentialsTokenAndCachesIt() throws Exception {
        ServiceTokenSupplier supplier = supplier();

        assertThat(supplier.get()).isEqualTo("t1");
        assertThat(supplier.get()).isEqualTo("t1");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(lastForm.get()).contains("grant_type=client_credentials").contains("client_id=workflow-service")
                .contains("client_secret=secret");
    }

    @Test
    void refreshesTokensThatAreAboutToExpire() throws Exception {
        response = "{\"access_token\":\"short\",\"expires_in\":10}";
        ServiceTokenSupplier supplier = supplier();

        supplier.get();
        response = "{\"access_token\":\"fresh\",\"expires_in\":300}";

        assertThat(supplier.get()).isEqualTo("fresh");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void failsWhenEndpointReturnsNoToken() throws Exception {
        response = "{\"error\":\"unauthorized_client\"}";

        assertThatThrownBy(() -> supplier().get()).isInstanceOf(IllegalStateException.class);
    }

    private ServiceTokenSupplier supplier() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            lastForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new ServiceTokenSupplier(new AuthProperties(
                "http://localhost:" + server.getAddress().getPort() + "/token", "workflow-service", "secret"),
                RestClient.create());
    }
}
