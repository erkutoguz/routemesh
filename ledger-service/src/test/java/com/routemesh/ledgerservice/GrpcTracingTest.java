package com.routemesh.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;

import com.routemesh.contracts.ledger.GetClearingBalancesRequest;
import com.routemesh.contracts.ledger.LedgerServiceGrpc;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

import io.grpc.ManagedChannel;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

/** gRPC çağrısında izleme bağlamı istemciden sunucuya taşınır; sunucu span'ı istemci span'ının çocuğudur. */
@SpringBootTest(properties = {
        "spring.grpc.server.port=0",
        "routemesh.ledger.integrity.scheduler-enabled=false",
        "management.tracing.export.enabled=true",
        "management.tracing.export.otlp.enabled=false",
        "management.opentelemetry.tracing.export.schedule-delay=50ms"})
@AutoConfigureTestGrpcTransport
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class, GrpcTracingTest.SpanCollector.class})
class GrpcTracingTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class SpanCollector {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    private InMemorySpanExporter spans;

    @Autowired
    private Tracer tracer;

    @Autowired
    private GrpcChannelFactory channels;

    @Test
    void aTraceCrossesTheGrpcBoundaryFromClientToServer() {
        spans.reset();
        ManagedChannel channel = channels.createChannel("test");
        LedgerServiceGrpc.LedgerServiceBlockingStub stub = LedgerServiceGrpc.newBlockingStub(channel)
                .withInterceptors(TestJwts.bearer(TestJwts.token("workflow-sa", "WORKFLOW")));
        Span parent = tracer.nextSpan().name("test-parent").start();
        String traceId;
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            traceId = parent.context().traceId();
            stub.getClearingBalances(GetClearingBalancesRequest.newBuilder()
                    .addShipmentIds(UUID.randomUUID().toString()).build());
        } finally {
            parent.end();
            channel.shutdownNow();
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<SpanData> inTrace = spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getTraceId().equals(traceId)).toList();
            SpanData client = inTrace.stream().filter(s -> s.getKind() == SpanKind.CLIENT).findFirst().orElseThrow();
            SpanData server = inTrace.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
            assertThat(client.getName()).contains("GetClearingBalances");
            assertThat(server.getParentSpanId()).isEqualTo(client.getSpanId());
        });
    }
}
