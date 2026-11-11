package com.routemesh.workflowservice.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.protobuf.Timestamp;
import com.routemesh.contracts.common.Money;
import com.routemesh.contracts.ledger.EntryDirection;
import com.routemesh.contracts.ledger.GetBalanceRequest;
import com.routemesh.contracts.ledger.GetBalanceResponse;
import com.routemesh.contracts.ledger.LedgerServiceGrpc;
import com.routemesh.contracts.ledger.PostTransactionRequest;
import com.routemesh.contracts.ledger.PostTransactionResponse;
import com.routemesh.contracts.payment.FindPaymentByShipmentRequest;
import com.routemesh.contracts.payment.PaymentCommandResponse;
import com.routemesh.contracts.payment.PaymentIntent;
import com.routemesh.contracts.payment.PaymentServiceGrpc;
import com.routemesh.contracts.payment.RefundPaymentRequest;
import com.routemesh.contracts.pricing.GetQuoteRequest;
import com.routemesh.contracts.pricing.PricingServiceGrpc;
import com.routemesh.contracts.pricing.Quote;
import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.ShipmentStatus;
import com.routemesh.workflowservice.settlement.SettlementActivities;
import com.routemesh.workflowservice.settlement.SettlementContext;
import com.routemesh.workflowservice.settlement.SettlementKind;
import com.routemesh.workflowservice.settlement.SettlementTemplates;

import io.grpc.Status;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;

class SettlementActivitiesImplTest {

    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments =
            mock(ShipmentServiceGrpc.ShipmentServiceBlockingStub.class);
    private final PricingServiceGrpc.PricingServiceBlockingStub pricing =
            mock(PricingServiceGrpc.PricingServiceBlockingStub.class);
    private final LedgerServiceGrpc.LedgerServiceBlockingStub ledger =
            mock(LedgerServiceGrpc.LedgerServiceBlockingStub.class);
    private final PaymentServiceGrpc.PaymentServiceBlockingStub payments =
            mock(PaymentServiceGrpc.PaymentServiceBlockingStub.class);

    private final UUID shipmentId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID courierId = UUID.randomUUID();
    private final String quoteId = UUID.randomUUID().toString();

    private TestActivityEnvironment env;
    private SettlementActivities activities;

    @BeforeEach
    void setUp() {
        env = TestActivityEnvironment.newInstance();
        env.registerActivitiesImplementations(new SettlementActivitiesImpl(shipments, pricing, ledger, payments));
        activities = env.newActivityStub(SettlementActivities.class, ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2)
                        .setInitialInterval(Duration.ofMillis(10)).build())
                .build());
        when(pricing.getQuote(any(GetQuoteRequest.class))).thenReturn(Quote.newBuilder().setSlaMinutes(240).build());
        when(ledger.getBalance(any(GetBalanceRequest.class))).thenReturn(balance(1001));
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void load_collectsShipmentSlaAndEscrowBalance() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(delivered().build());

        SettlementContext context = activities.load(shipmentId, SettlementKind.DELIVERY);

        assertThat(context).isEqualTo(new SettlementContext(shipmentId.toString(), merchantId.toString(),
                courierId.toString(), 1001, 1001, 1_800_000_000_000L, 1_800_000_000_000L + 7_200_000L, 240));
        ArgumentCaptor<GetBalanceRequest> balance = ArgumentCaptor.forClass(GetBalanceRequest.class);
        verify(ledger).getBalance(balance.capture());
        assertThat(balance.getValue().getAccountCode()).isEqualTo("LIABILITY:ESCROW");
        assertThat(balance.getValue().getShipmentId()).isEqualTo(shipmentId.toString());
        ArgumentCaptor<GetQuoteRequest> quote = ArgumentCaptor.forClass(GetQuoteRequest.class);
        verify(pricing).getQuote(quote.capture());
        assertThat(quote.getValue().getQuoteId()).isEqualTo(quoteId);
    }

    @Test
    void load_acceptsAnAlreadyDistributedEscrow() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(delivered().build());
        when(ledger.getBalance(any(GetBalanceRequest.class))).thenReturn(balance(0));

        assertThat(activities.load(shipmentId, SettlementKind.DELIVERY).escrowBalanceMinor()).isZero();
    }

    @Test
    void load_rejectsShipmentsThatAreNotDelivered() {
        when(shipments.getShipment(any(GetShipmentRequest.class)))
                .thenReturn(delivered().setStatus(ShipmentStatus.SHIPMENT_STATUS_OUT_FOR_DELIVERY).build());

        assertRejected(() -> activities.load(shipmentId, SettlementKind.DELIVERY), "expected SHIPMENT_STATUS_DELIVERED");
        verify(ledger, never()).getBalance(any());
    }

    @Test
    void load_rejectsAnEscrowBalanceThatDoesNotMatchTheAmount() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(delivered().build());
        when(ledger.getBalance(any(GetBalanceRequest.class))).thenReturn(balance(500));

        assertRejected(() -> activities.load(shipmentId, SettlementKind.DELIVERY), "escrow balance 500");
    }

    @Test
    void load_rejectsDeliveredShipmentsWithoutMilestonesOrCourier() {
        when(shipments.getShipment(any(GetShipmentRequest.class)))
                .thenReturn(delivered().clearWarehouseReceivedAt().build());
        assertRejected(() -> activities.load(shipmentId, SettlementKind.DELIVERY), "milestones");

        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(delivered().clearCourierId().build());
        assertRejected(() -> activities.load(shipmentId, SettlementKind.DELIVERY), "needs the courier");
    }

    @Test
    void load_translatesAnUnknownShipmentToARejection() {
        when(shipments.getShipment(any(GetShipmentRequest.class)))
                .thenThrow(new io.grpc.StatusRuntimeException(Status.NOT_FOUND));

        assertRejected(() -> activities.load(shipmentId, SettlementKind.DELIVERY), "NOT_FOUND");
    }

    @Test
    void load_forAReturnNeedsTheCourierButNotTheMilestonesOrTheQuote() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(
                delivered().setStatus(ShipmentStatus.SHIPMENT_STATUS_CANCELLED).clearWarehouseReceivedAt()
                        .clearDeliveredAt().build());

        SettlementContext context = activities.load(shipmentId, SettlementKind.RETURN);

        assertThat(context).isEqualTo(new SettlementContext(shipmentId.toString(), merchantId.toString(),
                courierId.toString(), 1001, 1001, 0, 0, 0));
        verify(pricing, never()).getQuote(any());

        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(
                delivered().setStatus(ShipmentStatus.SHIPMENT_STATUS_CANCELLED).clearCourierId().build());
        assertRejected(() -> activities.load(shipmentId, SettlementKind.RETURN), "needs the courier");
    }

    @Test
    void load_forACancellationOrALossDoesNotNeedTheCourier() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(
                delivered().setStatus(ShipmentStatus.SHIPMENT_STATUS_CANCELLED).clearCourierId().build());
        assertThat(activities.load(shipmentId, SettlementKind.CANCEL).courierId()).isEmpty();

        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(
                delivered().setStatus(ShipmentStatus.SHIPMENT_STATUS_LOST).clearCourierId().build());
        assertThat(activities.load(shipmentId, SettlementKind.LOST).amountMinor()).isEqualTo(1001);
    }

    @Test
    void load_rejectsAShipmentWhoseStateDoesNotMatchTheTemplate() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(delivered().build());

        assertRejected(() -> activities.load(shipmentId, SettlementKind.CANCEL), "expected SHIPMENT_STATUS_CANCELLED");
        assertRejected(() -> activities.load(shipmentId, SettlementKind.RETURN), "expected SHIPMENT_STATUS_CANCELLED");
        assertRejected(() -> activities.load(shipmentId, SettlementKind.LOST), "expected SHIPMENT_STATUS_LOST");
    }

    @Test
    void load_forARefundAlsoChecksTheEscrowBalance() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(
                delivered().setStatus(ShipmentStatus.SHIPMENT_STATUS_LOST).build());
        when(ledger.getBalance(any(GetBalanceRequest.class))).thenReturn(balance(300));

        assertRejected(() -> activities.load(shipmentId, SettlementKind.LOST), "escrow balance 300");
    }

    @Test
    void postLedger_sendsTheLinesWithAWorkflowScopedIdempotencyKey() {
        when(ledger.postTransaction(any(PostTransactionRequest.class)))
                .thenReturn(PostTransactionResponse.getDefaultInstance());

        activities.postLedger(shipmentId, "DELIVERY_SETTLEMENT", List.of(
                new SettlementTemplates.Line("LIABILITY:ESCROW", true, 1001),
                new SettlementTemplates.Line("REVENUE:PLATFORM_FEE", false, 1001)));

        ArgumentCaptor<PostTransactionRequest> request = ArgumentCaptor.forClass(PostTransactionRequest.class);
        verify(ledger).postTransaction(request.capture());
        PostTransactionRequest sent = request.getValue();
        assertThat(sent.getIdempotencyKey()).endsWith(":DELIVERY_SETTLEMENT");
        assertThat(sent.getTransactionType()).isEqualTo("DELIVERY_SETTLEMENT");
        assertThat(sent.getShipmentId()).isEqualTo(shipmentId.toString());
        assertThat(sent.getEntriesList()).hasSize(2);
        assertThat(sent.getEntries(0).getDirection()).isEqualTo(EntryDirection.ENTRY_DIRECTION_DEBIT);
        assertThat(sent.getEntries(1).getDirection()).isEqualTo(EntryDirection.ENTRY_DIRECTION_CREDIT);
        assertThat(sent.getEntries(0).getAmount().getAmountMinor()).isEqualTo(1001);
        assertThat(sent.getEntries(0).getAmount().getCurrency()).isEqualTo("TRY");
    }

    @Test
    void postLedger_businessRejectionIsNotRetriedButOutagesAre() {
        when(ledger.postTransaction(any(PostTransactionRequest.class)))
                .thenThrow(new io.grpc.StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("overdraft")));

        assertRejected(() -> activities.postLedger(shipmentId, "DELIVERY_SETTLEMENT",
                List.of(new SettlementTemplates.Line("LIABILITY:ESCROW", true, 1))), "FAILED_PRECONDITION");
        verify(ledger, org.mockito.Mockito.times(1)).postTransaction(any());
    }

    @Test
    void refundPayment_refundsTheCapturedPaymentWithAnOperationScopedKey() {
        String paymentId = UUID.randomUUID().toString();
        when(payments.findPaymentByShipment(any(FindPaymentByShipmentRequest.class)))
                .thenReturn(PaymentIntent.newBuilder().setId(paymentId).build());
        when(payments.refundPayment(any(RefundPaymentRequest.class)))
                .thenReturn(PaymentCommandResponse.getDefaultInstance());

        activities.refundPayment(shipmentId, "SLA_PENALTY_REFUND", 54, "SLA_PENALTY");

        ArgumentCaptor<RefundPaymentRequest> request = ArgumentCaptor.forClass(RefundPaymentRequest.class);
        verify(payments).refundPayment(request.capture());
        assertThat(request.getValue().getPaymentId()).isEqualTo(paymentId);
        assertThat(request.getValue().getIdempotencyKey()).endsWith(":SLA_PENALTY_REFUND:refund");
        assertThat(request.getValue().getAmount().getAmountMinor()).isEqualTo(54);
        assertThat(request.getValue().getReason()).isEqualTo("SLA_PENALTY");
    }

    @Test
    void refundPayment_withoutACapturedPaymentIsRejected() {
        when(payments.findPaymentByShipment(any(FindPaymentByShipmentRequest.class)))
                .thenThrow(new io.grpc.StatusRuntimeException(Status.NOT_FOUND));

        assertRejected(() -> activities.refundPayment(shipmentId, "SLA_PENALTY_REFUND", 54, "SLA_PENALTY"),
                "NOT_FOUND");
        verify(payments, never()).refundPayment(any());
    }

    // --- yardımcılar ---

    private Shipment.Builder delivered() {
        return Shipment.newBuilder()
                .setId(shipmentId.toString())
                .setMerchantId(merchantId.toString())
                .setCourierId(courierId.toString())
                .setStatus(ShipmentStatus.SHIPMENT_STATUS_DELIVERED)
                .setQuoteId(quoteId)
                .setFinalAmount(Money.newBuilder().setAmountMinor(1001).setCurrency("TRY"))
                .setWarehouseReceivedAt(Timestamp.newBuilder().setSeconds(1_800_000_000L))
                .setDeliveredAt(Timestamp.newBuilder().setSeconds(1_800_000_000L + 7200));
    }

    private static GetBalanceResponse balance(long amountMinor) {
        return GetBalanceResponse.newBuilder()
                .setBalance(Money.newBuilder().setAmountMinor(amountMinor).setCurrency("TRY")).build();
    }

    private static void assertRejected(Runnable call, String messagePart) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ActivityFailure.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(ApplicationFailure.class);
                    ApplicationFailure failure = (ApplicationFailure) e.getCause();
                    assertThat(failure.isNonRetryable()).isTrue();
                    assertThat(failure.getOriginalMessage()).contains(messagePart);
                });
    }
}
