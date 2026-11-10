package com.routemesh.workflowservice.workflow;

import java.time.Duration;
import java.util.Set;

import com.routemesh.workflowservice.activity.ShipmentActivities;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;

public class ShipmentWorkflowImpl implements ShipmentWorkflow {

    static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(15);
    static final Duration CAPTURE_TIMEOUT = Duration.ofMinutes(5);
    static final Duration POLL_INITIAL = Duration.ofSeconds(5);
    static final Duration POLL_MAX = Duration.ofSeconds(30);
    private static final Set<String> DEAD_PAYMENT = Set.of("FAILED", "VOIDED", "EXPIRED", "REFUNDED");

    private final ShipmentActivities activities = Workflow.newActivityStub(ShipmentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setScheduleToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2)
                            .setMaximumInterval(Duration.ofSeconds(15))
                            .build())
                    .build());

    private final ShipmentActivities compensations = Workflow.newActivityStub(ShipmentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .build())
                    .build());

    private CheckoutInfo checkout;
    private boolean finished;
    private boolean paid;
    private boolean dispatchRequested;
    private long lastDispatchVersion;
    private String failureReason = "workflow finished before a payment was created";

    @Override
    public ShipmentWorkflowResult run(ShipmentWorkflowInput input) {
        long deadline = Workflow.currentTimeMillis() + PAYMENT_TIMEOUT.toMillis();
        Saga saga = new Saga(new Saga.Options.Builder().setContinueWithError(true).build());
        String paymentId = null;
        try {
            QuoteInfo quote = activities.redeemQuote(input.quoteId(), input.merchantId(), input.shipmentId());
            long amount = quote.finalAmountMinor();

            saga.addCompensation(() -> compensations.cancelShipment(input.shipmentId(), "checkout failed"));
            activities.createShipment(input, quote);

            PaymentInfo payment = activities.createPaymentIntent(input.shipmentId(), amount);
            paymentId = payment.paymentId();
            String createdPaymentId = paymentId;
            saga.addCompensation(() -> compensations.releasePayment(createdPaymentId, amount, "checkout failed"));
            checkout = new CheckoutInfo(paymentId, payment.checkoutUrl());

            payment = awaitAuthorization(payment, deadline);
            if (!"AUTHORIZED".equals(payment.status())) {
                return cancelled(saga, "payment " + payment.status(), paymentId);
            }

            payment = activities.capturePayment(paymentId);
            payment = awaitCapture(payment);
            if (!"CAPTURED".equals(payment.status())) {
                return cancelled(saga, "capture ended as " + payment.status(), paymentId);
            }

            saga.addCompensation(() -> compensations.reverseEscrow(input.shipmentId(), amount));
            activities.postEscrow(input.shipmentId(), amount);
            activities.markPaid(input.shipmentId());
            paid = true;
            if (Workflow.getVersion("dispatch-after-paid", Workflow.DEFAULT_VERSION, 1) == 1) {
                awaitDispatch(input);
            }
            finish();
            return new ShipmentWorkflowResult(ShipmentWorkflowResult.PAID, null, paymentId);
        } catch (CanceledFailure e) {
            if (paid) {
                finish();
                throw e;
            }
            failureReason = "workflow cancelled";
            CancellationScope detached = Workflow.newDetachedCancellationScope(saga::compensate);
            detached.run();
            finish();
            throw e;
        } catch (ActivityFailure e) {
            return cancelled(saga, describe(e), paymentId);
        }
    }

    @Override
    public CheckoutInfo awaitCheckout() {
        Workflow.await(() -> checkout != null || finished);
        if (checkout == null) {
            throw ApplicationFailure.newFailure(failureReason, "CHECKOUT_UNAVAILABLE");
        }
        return new CheckoutInfo(checkout.paymentId(), checkout.checkoutUrl(), paid);
    }

    @Override
    public void readyForDispatch(long shipmentVersion) {
        if (shipmentVersion > lastDispatchVersion) {
            lastDispatchVersion = shipmentVersion;
            dispatchRequested = true;
        }
    }

    /** PAID sonrası gönderi hazır olunca dağıtımı başlatır; atama olana kadar her yeni hazır olayında yeniden dener. */
    private void awaitDispatch(ShipmentWorkflowInput input) {
        int attempt = 0;
        while (true) {
            Workflow.await(() -> dispatchRequested);
            dispatchRequested = false;
            DispatchWorkflow dispatch = Workflow.newChildWorkflowStub(DispatchWorkflow.class,
                    ChildWorkflowOptions.newBuilder()
                            .setWorkflowId("dispatch-" + input.shipmentId() + "-" + (++attempt))
                            .build());
            try {
                DispatchResult result = dispatch.dispatch(new DispatchInput(input.shipmentId()));
                if (DispatchResult.ASSIGNED.equals(result.outcome())) {
                    return;
                }
            } catch (ChildWorkflowFailure e) {
                Workflow.getLogger(ShipmentWorkflowImpl.class).warn("dispatch attempt {} failed", attempt, e);
            }
        }
    }

    private PaymentInfo awaitAuthorization(PaymentInfo payment, long deadline) {
        Duration delay = POLL_INITIAL;
        while ("CREATED".equals(payment.status())) {
            long remaining = deadline - Workflow.currentTimeMillis();
            if (remaining <= 0) {
                return activities.expirePayment(payment.paymentId());
            }
            Workflow.sleep(Duration.ofMillis(Math.min(delay.toMillis(), remaining)));
            payment = activities.getPayment(payment.paymentId());
            delay = next(delay);
        }
        return payment;
    }

    private PaymentInfo awaitCapture(PaymentInfo payment) {
        long deadline = Workflow.currentTimeMillis() + CAPTURE_TIMEOUT.toMillis();
        Duration delay = POLL_INITIAL;
        while (!"CAPTURED".equals(payment.status()) && !DEAD_PAYMENT.contains(payment.status())
                && Workflow.currentTimeMillis() < deadline) {
            Workflow.sleep(delay);
            payment = activities.getPayment(payment.paymentId());
            delay = next(delay);
        }
        return payment;
    }

    private ShipmentWorkflowResult cancelled(Saga saga, String reason, String paymentId) {
        failureReason = reason;
        saga.compensate();
        finish();
        return new ShipmentWorkflowResult(ShipmentWorkflowResult.CANCELLED, reason, paymentId);
    }

    private void finish() {
        finished = true;
        Workflow.await(Workflow::isEveryHandlerFinished);
    }

    private static Duration next(Duration delay) {
        Duration doubled = delay.multipliedBy(2);
        return doubled.compareTo(POLL_MAX) > 0 ? POLL_MAX : doubled;
    }

    private static String describe(ActivityFailure e) {
        Throwable cause = e.getCause();
        if (cause instanceof ApplicationFailure failure) {
            return failure.getOriginalMessage();
        }
        return cause == null ? e.getMessage() : cause.getMessage();
    }
}
