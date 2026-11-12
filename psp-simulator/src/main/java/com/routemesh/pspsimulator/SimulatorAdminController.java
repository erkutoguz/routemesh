package com.routemesh.pspsimulator;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Gerçek bir PSP'nin mutabakat raporunun karşılığı ({@code GET /v1/reports/payments}) ve testler için hata
 * enjeksiyonu. Kimlik doğrulaması yoktur (simülatör).
 */
@RestController
public class SimulatorAdminController {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    public record FaultsRequest(@JsonAlias("dropWebhooks") Boolean dropWebhooks) {
    }

    public record TamperRequest(@JsonAlias("capturedMinor") Long capturedMinor,
                                @JsonAlias("refundedMinor") Long refundedMinor, String status) {
    }

    private final SimPaymentStore payments;
    private final SimFaults faults;

    public SimulatorAdminController(SimPaymentStore payments, SimFaults faults) {
        this.payments = payments;
        this.faults = faults;
    }

    /** {@code updated_at} artan sırada, {@code updatedFrom} dahil ve {@code updatedTo} hariç, imleçli sayfalar. */
    @GetMapping("/v1/reports/payments")
    public Map<String, Object> report(@RequestParam(required = false) Instant updatedFrom,
                                      @RequestParam(required = false) Instant updatedTo,
                                      @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                                      @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        if (updatedFrom != null && updatedTo != null && !updatedFrom.isBefore(updatedTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "updatedFrom must be before updatedTo");
        }
        Instant afterAt = null;
        String afterId = null;
        if (cursor != null) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                        .split(":", 2);
                afterAt = Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS);
                afterId = parts[1];
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is not valid");
            }
        }
        Instant from = updatedFrom;
        Instant to = updatedTo;
        Instant cursorAt = afterAt;
        String cursorId = afterId;
        List<Snapshot> matching = payments.all().stream()
                .map(Snapshot::of)
                .filter(s -> from == null || !s.updatedAt().isBefore(from))
                .filter(s -> to == null || s.updatedAt().isBefore(to))
                .filter(s -> cursorAt == null || compare(s.updatedAt(), s.id(), cursorAt, cursorId) > 0)
                .sorted(Comparator.comparing(Snapshot::updatedAt).thenComparing(Snapshot::id))
                .limit(limit + 1L)
                .toList();
        boolean more = matching.size() > limit;
        List<Snapshot> page = more ? matching.subList(0, limit) : matching;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", page.stream().map(Snapshot::toView).toList());
        response.put("next_cursor", more ? encode(page.getLast()) : null);
        return response;
    }

    @GetMapping("/v1/sim/faults")
    public Map<String, Object> faults() {
        return Map.of("drop_webhooks", faults.dropWebhooks());
    }

    @PostMapping("/v1/sim/faults")
    public Map<String, Object> setFaults(@RequestBody FaultsRequest request) {
        if (request.dropWebhooks() != null) {
            faults.dropWebhooks(request.dropWebhooks());
        }
        return faults();
    }

    @PostMapping("/v1/sim/payments/{id}/tamper")
    public Map<String, Object> tamper(@PathVariable String id, @RequestBody TamperRequest request) {
        SimPayment payment = payments.get(id);
        if (payment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "payment " + id + " does not exist");
        }
        if ((request.capturedMinor() != null && request.capturedMinor() < 0)
                || (request.refundedMinor() != null && request.refundedMinor() < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amounts must not be negative");
        }
        SimPayment.Status status = null;
        if (request.status() != null) {
            try {
                status = SimPayment.Status.valueOf(request.status());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status: " + request.status());
            }
        }
        synchronized (payment) {
            payment.tamper(request.capturedMinor(), request.refundedMinor(), status);
            return Snapshot.of(payment).toView();
        }
    }

    private static int compare(Instant at, String id, Instant otherAt, String otherId) {
        int byTime = at.compareTo(otherAt);
        return byTime != 0 ? byTime : id.compareTo(otherId);
    }

    private static String encode(Snapshot last) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, last.updatedAt());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + ":" + last.id()).getBytes(StandardCharsets.UTF_8));
    }

    private record Snapshot(String id, String reference, String status, String currency, long amountMinor,
                            long capturedMinor, long refundedMinor, Instant createdAt, Instant updatedAt) {

        static Snapshot of(SimPayment payment) {
            synchronized (payment) {
                return new Snapshot(payment.id(), payment.reference(), payment.status().name(), payment.currency(),
                        payment.amountMinor(), payment.capturedMinor(), payment.refundedMinor(),
                        payment.createdAt(), payment.updatedAt());
            }
        }

        Map<String, Object> toView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", id);
            view.put("reference", reference);
            view.put("status", status);
            view.put("currency", currency);
            view.put("amount_minor", amountMinor);
            view.put("captured_minor", capturedMinor);
            view.put("refunded_minor", refundedMinor);
            view.put("created_at", createdAt);
            view.put("updated_at", updatedAt);
            return view;
        }
    }
}
