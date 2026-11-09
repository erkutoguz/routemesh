package com.routemesh.workflowservice.workflow;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.routemesh.workflowservice.activity.ShipmentActivities;

import io.temporal.failure.ApplicationFailure;

public class FakeActivities implements ShipmentActivities {

    public static final String PAYMENT_ID = "pay-1";

    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final Map<String, RuntimeException> failures = new ConcurrentHashMap<>();
    public final AtomicInteger transientCreateFailures = new AtomicInteger();

    public int authorizeAfterPolls = 2;
    public int captureAfterPolls = 0;
    public String expireResult = "EXPIRED";
    public String declineAtPoll;

    private String status = "CREATED";
    private int polls;
    private int capturePolls;
    private boolean captureRequested;

    public static ApplicationFailure rejected(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, "REJECTED");
    }

    @Override
    public QuoteInfo redeemQuote(UUID quoteId, UUID merchantId, UUID shipmentId) {
        record("redeem");
        return new QuoteInfo(1001, 800, 40.98, 29.03);
    }

    @Override
    public void createShipment(ShipmentWorkflowInput input, QuoteInfo quote) {
        record("create");
        if (transientCreateFailures.getAndDecrement() > 0) {
            throw new IllegalStateException("shipment-service unavailable");
        }
    }

    @Override
    public PaymentInfo createPaymentIntent(UUID shipmentId, long amountMinor) {
        record("intent");
        return new PaymentInfo(PAYMENT_ID, status, "http://psp/checkout/" + PAYMENT_ID, false);
    }

    @Override
    public PaymentInfo getPayment(String paymentId) {
        record("getPayment");
        polls++;
        if (declineAtPoll != null && polls >= authorizeAfterPolls) {
            status = declineAtPoll;
        } else if ("CREATED".equals(status) && polls >= authorizeAfterPolls) {
            status = "AUTHORIZED";
        } else if ("AUTHORIZED".equals(status) && captureRequested && ++capturePolls >= captureAfterPolls) {
            status = "CAPTURED";
        }
        return new PaymentInfo(paymentId, status, null, false);
    }

    @Override
    public PaymentInfo expirePayment(String paymentId) {
        record("expire");
        if ("CREATED".equals(status)) {
            status = expireResult;
        }
        return new PaymentInfo(paymentId, status, null, false);
    }

    @Override
    public PaymentInfo capturePayment(String paymentId) {
        record("capture");
        captureRequested = true;
        if (captureAfterPolls == 0) {
            status = "CAPTURED";
            return new PaymentInfo(paymentId, status, null, false);
        }
        return new PaymentInfo(paymentId, status, null, true);
    }

    @Override
    public void postEscrow(UUID shipmentId, long amountMinor) {
        record("escrow");
    }

    @Override
    public void markPaid(UUID shipmentId) {
        record("paid");
    }

    @Override
    public void cancelShipment(UUID shipmentId, String reason) {
        record("cancelShipment");
    }

    @Override
    public void releasePayment(String paymentId, long amountMinor, String reason) {
        record("releasePayment");
    }

    @Override
    public void reverseEscrow(UUID shipmentId, long amountMinor) {
        record("reverseEscrow");
    }

    private void record(String name) {
        calls.add(name);
        RuntimeException failure = failures.get(name);
        if (failure != null) {
            throw failure;
        }
    }
}
