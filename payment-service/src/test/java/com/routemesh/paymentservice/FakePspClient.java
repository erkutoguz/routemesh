package com.routemesh.paymentservice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.routemesh.paymentservice.psp.PspClient;
import com.routemesh.paymentservice.psp.PspRejectedException;
import com.routemesh.paymentservice.psp.PspUnavailableException;

public class FakePspClient implements PspClient {

    public record Call(String operation, String pspPaymentId, String idempotencyKey, long amountMinor) {
    }

    public enum Mode {
        OK,
        REJECT,
        UNAVAILABLE
    }

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile Mode mode = Mode.OK;

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public List<Call> calls() {
        return new ArrayList<>(calls);
    }

    public List<Call> calls(String operation) {
        return calls().stream().filter(c -> c.operation().equals(operation)).toList();
    }

    public void reset() {
        calls.clear();
        mode = Mode.OK;
        report.clear();
        reportFailure = false;
    }

    private final Map<String, PspPayment> report = new LinkedHashMap<>();
    private volatile boolean reportFailure;

    /** Mutabakat testleri için PSP'nin raporunda bulunan ödemeyi ayarlar (aynı kimlik üzerine yazar). */
    public void pspHas(PspPayment payment) {
        synchronized (report) {
            report.put(payment.id(), payment);
        }
    }

    public void reportFails(boolean fails) {
        this.reportFailure = fails;
    }

    @Override
    public PspReportPage listPayments(Instant updatedFrom, Instant updatedTo, int limit, String cursor) {
        calls.add(new Call("list", cursor, "", limit));
        if (reportFailure) {
            throw new PspUnavailableException("report unavailable in test", null);
        }
        List<PspPayment> matching;
        synchronized (report) {
            matching = report.values().stream()
                    .filter(p -> updatedFrom == null || !p.updatedAt().isBefore(updatedFrom))
                    .filter(p -> updatedTo == null || p.updatedAt().isBefore(updatedTo))
                    .sorted(java.util.Comparator.comparing(PspPayment::updatedAt).thenComparing(PspPayment::id))
                    .toList();
        }
        int offset = cursor == null ? 0 : Integer.parseInt(cursor);
        int end = Math.min(matching.size(), offset + limit);
        return new PspReportPage(matching.subList(Math.min(offset, end), end),
                end < matching.size() ? String.valueOf(end) : null);
    }

    @Override
    public Optional<PspPayment> findPayment(String pspPaymentId) {
        calls.add(new Call("find", pspPaymentId, "", 0));
        if (reportFailure) {
            throw new PspUnavailableException("report unavailable in test", null);
        }
        synchronized (report) {
            return Optional.ofNullable(report.get(pspPaymentId));
        }
    }

    @Override
    public CreatedPayment createPayment(String idempotencyKey, long amountMinor, String currency,
                                        String callbackUrl, String reference) {
        calls.add(new Call("create", null, idempotencyKey, amountMinor));
        failIfConfigured();
        String id = "psp_" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes());
        return new CreatedPayment(id, "https://psp.test/checkout/" + id);
    }

    @Override
    public String capture(String pspPaymentId, String idempotencyKey) {
        calls.add(new Call("capture", pspPaymentId, idempotencyKey, 0));
        failIfConfigured();
        return pspPaymentId;
    }

    @Override
    public String voidPayment(String pspPaymentId, String idempotencyKey) {
        calls.add(new Call("void", pspPaymentId, idempotencyKey, 0));
        failIfConfigured();
        return pspPaymentId;
    }

    @Override
    public String refund(String pspPaymentId, String idempotencyKey, long amountMinor) {
        calls.add(new Call("refund", pspPaymentId, idempotencyKey, amountMinor));
        failIfConfigured();
        return "re_" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes());
    }

    private void failIfConfigured() {
        if (mode == Mode.REJECT) {
            throw new PspRejectedException("rejected by test");
        }
        if (mode == Mode.UNAVAILABLE) {
            throw new PspUnavailableException("unavailable in test", null);
        }
    }
}
