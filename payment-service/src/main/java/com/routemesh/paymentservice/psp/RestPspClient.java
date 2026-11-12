package com.routemesh.paymentservice.psp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.routemesh.paymentservice.config.PaymentProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;

@Component
public class RestPspClient implements PspClient {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestClient restClient;
    private final MeterRegistry meters;

    public RestPspClient(RestClient.Builder builder, PaymentProperties properties, MeterRegistry meters) {
        this.restClient = builder.baseUrl(properties.pspBaseUrl()).build();
        this.meters = meters;
    }

    @Override
    public CreatedPayment createPayment(String idempotencyKey, long amountMinor, String currency, String callbackUrl,
                                        String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amountMinor);
        body.put("currency", currency);
        body.put("callback_url", callbackUrl);
        body.put("reference", reference);
        JsonNode response = call("create_payment", () -> restClient.post().uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, idempotencyKey)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
        return new CreatedPayment(response.path("id").asString(), response.path("checkout_url").asString());
    }

    @Override
    public String capture(String pspPaymentId, String idempotencyKey) {
        JsonNode response = call("capture", () -> restClient.post().uri("/v1/payments/{id}/capture", pspPaymentId)
                .header(IDEMPOTENCY_KEY, idempotencyKey)
                .retrieve()
                .body(JsonNode.class));
        return response.path("id").asString();
    }

    @Override
    public String voidPayment(String pspPaymentId, String idempotencyKey) {
        JsonNode response = call("void", () -> restClient.post().uri("/v1/payments/{id}/void", pspPaymentId)
                .header(IDEMPOTENCY_KEY, idempotencyKey)
                .retrieve()
                .body(JsonNode.class));
        return response.path("id").asString();
    }

    @Override
    public String refund(String pspPaymentId, String idempotencyKey, long amountMinor) {
        JsonNode response = call("refund", () -> restClient.post().uri("/v1/payments/{id}/refunds", pspPaymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, idempotencyKey)
                .body(Map.of("amount_minor", amountMinor))
                .retrieve()
                .body(JsonNode.class));
        return response.path("id").asString();
    }

    @Override
    public PspReportPage listPayments(Instant updatedFrom, Instant updatedTo, int limit, String cursor) {
        JsonNode response = call("list_payments", () -> restClient.get().uri(uri -> {
            uri.path("/v1/reports/payments").queryParam("limit", limit);
            if (updatedFrom != null) {
                uri.queryParam("updatedFrom", updatedFrom.toString());
            }
            if (updatedTo != null) {
                uri.queryParam("updatedTo", updatedTo.toString());
            }
            if (cursor != null) {
                uri.queryParam("cursor", cursor);
            }
            return uri.build();
        }).retrieve().body(JsonNode.class));
        List<PspPayment> items = new ArrayList<>();
        response.path("items").forEach(item -> items.add(payment(item)));
        JsonNode next = response.path("next_cursor");
        return new PspReportPage(items, next.isNull() || next.isMissingNode() ? null : next.asString());
    }

    @Override
    public Optional<PspPayment> findPayment(String pspPaymentId) {
        try {
            JsonNode response = call("find_payment", () -> restClient.get().uri("/v1/payments/{id}", pspPaymentId)
                    .retrieve().body(JsonNode.class));
            return Optional.of(payment(response));
        } catch (PspRejectedException e) {
            if (e.httpStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static PspPayment payment(JsonNode node) {
        return new PspPayment(node.path("id").asString(), node.path("reference").asString(),
                node.path("status").asString(), node.path("amount_minor").asLong(),
                node.path("captured_minor").asLong(), node.path("refunded_minor").asLong(),
                Instant.parse(node.path("created_at").asString()), Instant.parse(node.path("updated_at").asString()));
    }

    // operation sabit bir kümedir; outcome: ok | rejected | unavailable
    private JsonNode call(String operation, Supplier<JsonNode> request) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "ok";
        try {
            JsonNode response = request.get();
            if (response == null) {
                outcome = "unavailable";
                throw new PspUnavailableException("PSP returned an empty response", null);
            }
            return response;
        } catch (HttpStatusCodeException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.is4xxClientError() && status.value() != 408 && status.value() != 429) {
                outcome = "rejected";
                throw new PspRejectedException("PSP rejected the request with " + status.value() + ": "
                        + e.getResponseBodyAsString(), status.value());
            }
            outcome = "unavailable";
            throw new PspUnavailableException("PSP responded with " + status.value(), e);
        } catch (RestClientException e) {
            outcome = "unavailable";
            throw new PspUnavailableException("PSP call failed: " + e.getMessage(), e);
        } finally {
            sample.stop(Timer.builder("routemesh.payment.psp.call.duration").tag("operation", operation)
                    .tag("outcome", outcome).publishPercentileHistogram().register(meters));
        }
    }
}
