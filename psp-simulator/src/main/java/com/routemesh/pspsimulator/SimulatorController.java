package com.routemesh.pspsimulator;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SimulatorController {

    public record CreatePaymentRequest(long amountMinor, String currency, String callbackUrl, String reference) {
    }

    public record RefundRequest(long amountMinor) {
    }

    public record CompleteRequest(String outcome, Integer delaySeconds, Integer duplicateCount) {
    }

    private final SimPaymentStore payments;
    private final Clock clock;
    private final Map<String, Map<String, Object>> idempotentResponses = new ConcurrentHashMap<>();
    private final WebhookSender webhookSender;

    public SimulatorController(WebhookSender webhookSender, SimPaymentStore payments, Clock clock) {
        this.webhookSender = webhookSender;
        this.payments = payments;
        this.clock = clock;
    }

    @PostMapping("/v1/payments")
    public synchronized ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CreatePaymentRequest request) {
        requireKey(key);
        Map<String, Object> cached = idempotentResponses.get("create:" + key);
        if (cached != null) {
            return ResponseEntity.status(HttpStatus.CREATED).body(cached);
        }
        if (request.amountMinor() <= 0 || !"TRY".equals(request.currency())
                || request.callbackUrl() == null || request.callbackUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "amount_minor must be positive, currency TRY and callback_url required");
        }
        SimPayment payment = new SimPayment("psp_" + UUID.randomUUID(), request.amountMinor(), request.currency(),
                request.callbackUrl(), request.reference(), clock);
        payments.put(payment);
        Map<String, Object> view = view(payment);
        idempotentResponses.put("create:" + key, view);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/v1/payments/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        SimPayment payment = find(id);
        synchronized (payment) {
            return view(payment);
        }
    }

    @PostMapping("/v1/sim/payments/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable String id,
                                                        @RequestBody(required = false) CompleteRequest request) {
        SimPayment payment = find(id);
        boolean decline = request != null && "DECLINE".equalsIgnoreCase(request.outcome());
        Duration delay = Duration.ofSeconds(request != null && request.delaySeconds() != null
                ? request.delaySeconds() : 0);
        int copies = request != null && request.duplicateCount() != null ? request.duplicateCount() : 1;
        synchronized (payment) {
            if (payment.status() != SimPayment.Status.CREATED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "payment is " + payment.status());
            }
            payment.status(decline ? SimPayment.Status.DECLINED : SimPayment.Status.AUTHORIZED);
            Map<String, Object> data = baseData(payment);
            if (decline) {
                data.put("decline_reason", "card_declined");
            }
            webhookSender.send(payment.callbackUrl(), decline ? "payment.declined" : "payment.authorized", data,
                    delay, copies);
            return ResponseEntity.accepted().body(view(payment));
        }
    }

    @PostMapping("/v1/payments/{id}/capture")
    public Map<String, Object> capture(@PathVariable String id,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireKey(key);
        SimPayment payment = find(id);
        synchronized (payment) {
            Map<String, Object> cached = idempotentResponses.get("capture:" + id + ":" + key);
            if (cached != null) {
                return cached;
            }
            if (payment.status() != SimPayment.Status.AUTHORIZED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "payment is " + payment.status());
            }
            payment.capture();
            webhookSender.send(payment.callbackUrl(), "payment.captured", baseData(payment), Duration.ZERO, 1);
            Map<String, Object> view = view(payment);
            idempotentResponses.put("capture:" + id + ":" + key, view);
            return view;
        }
    }

    @PostMapping("/v1/payments/{id}/void")
    public Map<String, Object> voidPayment(@PathVariable String id,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireKey(key);
        SimPayment payment = find(id);
        synchronized (payment) {
            Map<String, Object> cached = idempotentResponses.get("void:" + id + ":" + key);
            if (cached != null) {
                return cached;
            }
            if (payment.status() != SimPayment.Status.AUTHORIZED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "payment is " + payment.status());
            }
            payment.status(SimPayment.Status.VOIDED);
            webhookSender.send(payment.callbackUrl(), "payment.voided", baseData(payment), Duration.ZERO, 1);
            Map<String, Object> view = view(payment);
            idempotentResponses.put("void:" + id + ":" + key, view);
            return view;
        }
    }

    @PostMapping("/v1/payments/{id}/refunds")
    public Map<String, Object> refund(@PathVariable String id,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      @RequestBody RefundRequest request) {
        requireKey(key);
        SimPayment payment = find(id);
        synchronized (payment) {
            Map<String, Object> cached = idempotentResponses.get("refund:" + id + ":" + key);
            if (cached != null) {
                return cached;
            }
            if (payment.status() != SimPayment.Status.CAPTURED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "payment is " + payment.status());
            }
            if (request.amountMinor() <= 0 || request.amountMinor() > payment.amountMinor() - payment.refundedMinor()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "refund exceeds the refundable amount");
            }
            payment.addRefunded(request.amountMinor());
            String refundId = "re_" + UUID.randomUUID();
            Map<String, Object> data = baseData(payment);
            data.put("refund_id", refundId);
            data.put("refund_amount_minor", request.amountMinor());
            webhookSender.send(payment.callbackUrl(), "refund.succeeded", data, Duration.ZERO, 1);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", refundId);
            response.put("payment_id", payment.id());
            response.put("amount_minor", request.amountMinor());
            response.put("status", "SUCCEEDED");
            idempotentResponses.put("refund:" + id + ":" + key, response);
            return response;
        }
    }

    private SimPayment find(String id) {
        SimPayment payment = payments.get(id);
        if (payment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "payment " + id + " does not exist");
        }
        return payment;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
    }

    private static Map<String, Object> baseData(SimPayment payment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment_id", payment.id());
        data.put("reference", payment.reference());
        data.put("amount_minor", payment.amountMinor());
        data.put("currency", payment.currency());
        return data;
    }

    private static Map<String, Object> view(SimPayment payment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", payment.id());
        view.put("status", payment.status().name());
        view.put("amount_minor", payment.amountMinor());
        view.put("currency", payment.currency());
        view.put("reference", payment.reference());
        view.put("captured_minor", payment.capturedMinor());
        view.put("refunded_minor", payment.refundedMinor());
        view.put("created_at", payment.createdAt());
        view.put("updated_at", payment.updatedAt());
        view.put("checkout_url", "http://localhost:8085/checkout/" + payment.id());
        return view;
    }
}
