package com.routemesh.workflowservice.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.routemesh.contracts.common.LatLong;
import com.routemesh.contracts.common.Money;
import com.routemesh.contracts.ledger.EntryDirection;
import com.routemesh.contracts.ledger.LedgerServiceGrpc;
import com.routemesh.contracts.ledger.PostTransactionRequest;
import com.routemesh.contracts.ledger.PostTransactionResponse;
import com.routemesh.contracts.payment.PaymentCommandResponse;
import com.routemesh.contracts.payment.PaymentIntent;
import com.routemesh.contracts.payment.PaymentServiceGrpc;
import com.routemesh.contracts.payment.PaymentStatus;
import com.routemesh.contracts.payment.RefundPaymentRequest;
import com.routemesh.contracts.payment.VoidPaymentRequest;
import com.routemesh.contracts.pricing.GetQuoteRequest;
import com.routemesh.contracts.pricing.PricingServiceGrpc;
import com.routemesh.contracts.pricing.Quote;
import com.routemesh.contracts.pricing.RedeemQuoteRequest;
import com.routemesh.contracts.pricing.RedeemQuoteResponse;
import com.routemesh.contracts.shipment.CreateShipmentRequest;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.ShipmentStatus;
import com.routemesh.contracts.shipment.TransitionStatusRequest;
import com.routemesh.workflowservice.workflow.PaymentInfo;
import com.routemesh.workflowservice.workflow.QuoteInfo;
import com.routemesh.workflowservice.workflow.ShipmentWorkflowInput;

import io.grpc.Status;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;

class ShipmentActivitiesImplTest {

    private final PricingServiceGrpc.PricingServiceBlockingStub pricing =
            mock(PricingServiceGrpc.PricingServiceBlockingStub.class);
    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments =
            mock(ShipmentServiceGrpc.ShipmentServiceBlockingStub.class);
    private final PaymentServiceGrpc.PaymentServiceBlockingStub payments =
            mock(PaymentServiceGrpc.PaymentServiceBlockingStub.class);
    private final LedgerServiceGrpc.LedgerServiceBlockingStub ledger =
            mock(LedgerServiceGrpc.LedgerServiceBlockingStub.class);

    private final UUID shipmentId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID quoteId = UUID.randomUUID();

    private TestActivityEnvironment env;
    private ShipmentActivities activities;

    @BeforeEach
    void setUp() {
        env = TestActivityEnvironment.newInstance();
        env.registerActivitiesImplementations(new ShipmentActivitiesImpl(pricing, shipments, payments, ledger));
        activities = env.newActivityStub(ShipmentActivities.class, ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2)
                        .setInitialInterval(Duration.ofMillis(10)).build())
                .build());
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void redeemQuote_ofAnotherMerchant_isRejectedBeforeRedeeming() {
        when(pricing.getQuote(any(GetQuoteRequest.class)))
                .thenReturn(Quote.newBuilder().setMerchantId(UUID.randomUUID().toString()).build());

        assertThatThrownBy(() -> activities.redeemQuote(quoteId, merchantId, shipmentId))
                .isInstanceOf(ActivityFailure.class)
                .hasStackTraceContaining("quote not found");
        verify(pricing, never()).redeemQuote(any(RedeemQuoteRequest.class));
    }

    @Test
    void redeemQuote_redeemsWithStableKeyAndReturnsAmounts() {
        when(pricing.getQuote(any(GetQuoteRequest.class)))
                .thenReturn(Quote.newBuilder().setMerchantId(merchantId.toString()).build());
        when(pricing.redeemQuote(any(RedeemQuoteRequest.class))).thenReturn(RedeemQuoteResponse.newBuilder()
                .setQuote(Quote.newBuilder().setFinalAmount(money(1001)).setBasePrice(money(800))
                        .setDestination(LatLong.newBuilder().setLatitude(40.9).setLongitude(29.1)))
                .build());

        QuoteInfo info = activities.redeemQuote(quoteId, merchantId, shipmentId);

        ArgumentCaptor<RedeemQuoteRequest> request = ArgumentCaptor.forClass(RedeemQuoteRequest.class);
        verify(pricing).redeemQuote(request.capture());
        assertThat(request.getValue().getRedemptionKey()).endsWith(":redeem");
        assertThat(request.getValue().getShipmentId()).isEqualTo(shipmentId.toString());
        assertThat(info).isEqualTo(new QuoteInfo(1001, 800, 40.9, 29.1));
    }

    @Test
    void createShipment_passesDerivedShipmentIdAndQuoteAmounts() {
        activities.createShipment(new ShipmentWorkflowInput(shipmentId, merchantId, quoteId, "Ada", "+905551112233",
                "Moda"), new QuoteInfo(1001, 800, 40.9, 29.1));

        ArgumentCaptor<CreateShipmentRequest> request = ArgumentCaptor.forClass(CreateShipmentRequest.class);
        verify(shipments).createShipment(request.capture());
        assertThat(request.getValue().getShipmentId()).isEqualTo(shipmentId.toString());
        assertThat(request.getValue().getIdempotencyKey()).endsWith(":create");
        assertThat(request.getValue().getFinalAmount().getAmountMinor()).isEqualTo(1001);
        assertThat(request.getValue().getBasePrice().getAmountMinor()).isEqualTo(800);
        assertThat(request.getValue().getDeliveryLocation().getLatitude()).isEqualTo(40.9);
    }

    @Test
    void createShipment_carriesTheQuotedWeightWhenKnown() {
        activities.createShipment(new ShipmentWorkflowInput(shipmentId, merchantId, quoteId, "Ada", "+905551112233",
                "Moda"), new QuoteInfo(1001, 800, 40.9, 29.1, 2500));
        activities.createShipment(new ShipmentWorkflowInput(shipmentId, merchantId, quoteId, "Ada", "+905551112233",
                "Moda"), new QuoteInfo(1001, 800, 40.9, 29.1));

        ArgumentCaptor<CreateShipmentRequest> request = ArgumentCaptor.forClass(CreateShipmentRequest.class);
        verify(shipments, times(2)).createShipment(request.capture());
        assertThat(request.getAllValues().get(0).getWeightGrams()).isEqualTo(2500);
        assertThat(request.getAllValues().get(1).hasWeightGrams()).isFalse();
    }

    @Test
    void businessErrors_areNotRetried() {
        when(shipments.createShipment(any(CreateShipmentRequest.class)))
                .thenThrow(Status.INVALID_ARGUMENT.withDescription("bad phone").asRuntimeException());

        assertThatThrownBy(() -> activities.createShipment(new ShipmentWorkflowInput(shipmentId, merchantId, quoteId,
                "Ada", "1", "Moda"), new QuoteInfo(1, 1, 0, 0)))
                .isInstanceOf(ActivityFailure.class)
                .hasStackTraceContaining("INVALID_ARGUMENT: bad phone")
                .satisfies(e -> assertThat(((ApplicationFailure) e.getCause()).isNonRetryable()).isTrue());
    }

    @Test
    void transientErrors_areRetried() {
        when(shipments.createShipment(any(CreateShipmentRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());

        assertThatThrownBy(() -> activities.createShipment(new ShipmentWorkflowInput(shipmentId, merchantId, quoteId,
                "Ada", "+905551112233", "Moda"), new QuoteInfo(1, 1, 0, 0)))
                .isInstanceOf(ActivityFailure.class)
                .satisfies(e -> assertThat(((ApplicationFailure) e.getCause()).isNonRetryable()).isFalse());
    }

    @Test
    void releasePayment_picksTheRightUndoForEachStatus() {
        when(payments.getPayment(any())).thenReturn(payment(PaymentStatus.PAYMENT_STATUS_CREATED, 0));
        activities.releasePayment("p1", 1001, "r");
        verify(payments).expirePayment(any());

        when(payments.getPayment(any())).thenReturn(payment(PaymentStatus.PAYMENT_STATUS_AUTHORIZED, 0));
        activities.releasePayment("p1", 1001, "r");
        ArgumentCaptor<VoidPaymentRequest> voided = ArgumentCaptor.forClass(VoidPaymentRequest.class);
        verify(payments).voidPayment(voided.capture());
        assertThat(voided.getValue().getIdempotencyKey()).endsWith(":void");

        when(payments.getPayment(any())).thenReturn(payment(PaymentStatus.PAYMENT_STATUS_PARTIALLY_REFUNDED, 300));
        activities.releasePayment("p1", 1001, "r");
        ArgumentCaptor<RefundPaymentRequest> refund = ArgumentCaptor.forClass(RefundPaymentRequest.class);
        verify(payments).refundPayment(refund.capture());
        assertThat(refund.getValue().getAmount().getAmountMinor()).isEqualTo(701);
        assertThat(refund.getValue().getIdempotencyKey()).endsWith(":refund");

        when(payments.getPayment(any())).thenReturn(payment(PaymentStatus.PAYMENT_STATUS_REFUNDED, 1001));
        activities.releasePayment("p1", 1001, "r");
        when(payments.getPayment(any())).thenReturn(payment(PaymentStatus.PAYMENT_STATUS_VOIDED, 0));
        activities.releasePayment("p1", 1001, "r");
        verify(payments, times(1)).expirePayment(any());
        verify(payments, times(1)).voidPayment(any());
        verify(payments, times(1)).refundPayment(any());
    }

    @Test
    void cancelShipment_isIdempotentAndRefusesToCancelPaidShipments() {
        when(shipments.getShipment(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());
        activities.cancelShipment(shipmentId, "r");

        doReturn(shipment(ShipmentStatus.SHIPMENT_STATUS_CANCELLED)).when(shipments).getShipment(any());
        activities.cancelShipment(shipmentId, "r");
        verify(shipments, never()).transitionStatus(any());

        doReturn(shipment(ShipmentStatus.SHIPMENT_STATUS_PAID)).when(shipments).getShipment(any());
        assertThatThrownBy(() -> activities.cancelShipment(shipmentId, "r"))
                .isInstanceOf(ActivityFailure.class).hasStackTraceContaining("manual intervention");
        verify(shipments, never()).transitionStatus(any());

        doReturn(shipment(ShipmentStatus.SHIPMENT_STATUS_PENDING_PAYMENT)).when(shipments).getShipment(any());
        activities.cancelShipment(shipmentId, "payment EXPIRED");
        ArgumentCaptor<TransitionStatusRequest> transition = ArgumentCaptor.forClass(TransitionStatusRequest.class);
        verify(shipments).transitionStatus(transition.capture());
        assertThat(transition.getValue().getToStatus()).isEqualTo(ShipmentStatus.SHIPMENT_STATUS_CANCELLED);
        assertThat(transition.getValue().getExpectedFromStatus())
                .isEqualTo(ShipmentStatus.SHIPMENT_STATUS_PENDING_PAYMENT);
        assertThat(transition.getValue().getReason()).isEqualTo("payment EXPIRED");
    }

    @Test
    void escrow_isDebitClearingCreditEscrow_andReversalMirrorsTheOriginal() {
        when(ledger.postTransaction(any(PostTransactionRequest.class)))
                .thenReturn(PostTransactionResponse.newBuilder().setTransactionId("tx-1").build());

        activities.reverseEscrow(shipmentId, 1001);

        ArgumentCaptor<PostTransactionRequest> requests = ArgumentCaptor.forClass(PostTransactionRequest.class);
        verify(ledger, times(2)).postTransaction(requests.capture());
        PostTransactionRequest original = requests.getAllValues().get(0);
        PostTransactionRequest reversal = requests.getAllValues().get(1);
        assertThat(original.getTransactionType()).isEqualTo("ESCROW_FUNDED");
        assertThat(original.getIdempotencyKey()).endsWith(":escrow");
        assertThat(original.getEntries(0).getAccountCode()).isEqualTo("ASSET:PSP_CLEARING");
        assertThat(original.getEntries(0).getDirection()).isEqualTo(EntryDirection.ENTRY_DIRECTION_DEBIT);
        assertThat(original.getEntries(1).getAccountCode()).isEqualTo("LIABILITY:ESCROW");
        assertThat(original.getEntries(1).getDirection()).isEqualTo(EntryDirection.ENTRY_DIRECTION_CREDIT);
        assertThat(reversal.getReversalOf()).isEqualTo("tx-1");
        assertThat(reversal.getIdempotencyKey()).endsWith(":escrow-reversal");
        assertThat(reversal.getEntries(0).getDirection()).isEqualTo(EntryDirection.ENTRY_DIRECTION_CREDIT);
        assertThat(reversal.getEntries(1).getDirection()).isEqualTo(EntryDirection.ENTRY_DIRECTION_DEBIT);
    }

    @Test
    void capturePayment_reportsPendingStatus() {
        when(payments.capturePayment(any())).thenReturn(PaymentCommandResponse.newBuilder()
                .setPayment(payment(PaymentStatus.PAYMENT_STATUS_AUTHORIZED, 0)).setPending(true).build());

        PaymentInfo info = activities.capturePayment("p1");

        assertThat(info.status()).isEqualTo("AUTHORIZED");
        assertThat(info.pending()).isTrue();
    }

    private static PaymentIntent payment(PaymentStatus status, long refunded) {
        return PaymentIntent.newBuilder().setId("p1").setStatus(status).setAmount(money(1001))
                .setRefundedAmount(money(refunded)).build();
    }

    private static Shipment shipment(ShipmentStatus status) {
        return Shipment.newBuilder().setStatus(status).build();
    }

    private static Money money(long amountMinor) {
        return Money.newBuilder().setAmountMinor(amountMinor).setCurrency("TRY").build();
    }
}
