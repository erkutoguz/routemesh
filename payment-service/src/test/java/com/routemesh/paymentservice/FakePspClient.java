package com.routemesh.paymentservice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
