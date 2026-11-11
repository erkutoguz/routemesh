package com.routemesh.workflowservice.settlement;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.temporal.failure.ApplicationFailure;

public class FakeSettlementActivities implements SettlementActivities {

    public record Posted(String type, List<SettlementTemplates.Line> lines) {
    }

    public record Refund(String type, long amountMinor, String reason) {
    }

    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final List<Posted> posted = new CopyOnWriteArrayList<>();
    public final List<Refund> refunds = new CopyOnWriteArrayList<>();
    public final AtomicInteger transientRefundFailures = new AtomicInteger();

    public SettlementContext context;
    public RuntimeException loadFailure;
    public RuntimeException ledgerFailure;

    public FakeSettlementActivities(SettlementContext context) {
        this.context = context;
    }

    @Override
    public SettlementContext load(UUID shipmentId) {
        calls.add("load");
        if (loadFailure != null) {
            throw loadFailure;
        }
        return context;
    }

    @Override
    public void postLedger(UUID shipmentId, String transactionType, List<SettlementTemplates.Line> lines) {
        calls.add("ledger:" + transactionType);
        if (ledgerFailure != null) {
            throw ledgerFailure;
        }
        posted.add(new Posted(transactionType, lines));
    }

    @Override
    public void refundPayment(UUID shipmentId, String transactionType, long amountMinor, String reason) {
        calls.add("refund:" + transactionType);
        if (transientRefundFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("payment-service down");
        }
        refunds.add(new Refund(transactionType, amountMinor, reason));
    }

    public static ApplicationFailure rejected(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, "REJECTED");
    }
}
