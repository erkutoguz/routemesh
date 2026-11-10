package com.routemesh.workflowservice.activity;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.routemesh.contracts.common.Money;
import com.routemesh.contracts.ledger.EntryDirection;
import com.routemesh.contracts.ledger.LedgerEntry;
import com.routemesh.contracts.ledger.LedgerServiceGrpc;
import com.routemesh.contracts.ledger.PostTransactionRequest;
import com.routemesh.contracts.payment.CapturePaymentRequest;
import com.routemesh.contracts.payment.CreatePaymentIntentRequest;
import com.routemesh.contracts.payment.ExpirePaymentRequest;
import com.routemesh.contracts.payment.GetPaymentRequest;
import com.routemesh.contracts.payment.PaymentCommandResponse;
import com.routemesh.contracts.payment.PaymentIntent;
import com.routemesh.contracts.payment.PaymentServiceGrpc;
import com.routemesh.contracts.payment.RefundPaymentRequest;
import com.routemesh.contracts.payment.VoidPaymentRequest;
import com.routemesh.contracts.pricing.GetQuoteRequest;
import com.routemesh.contracts.pricing.PricingServiceGrpc;
import com.routemesh.contracts.pricing.Quote;
import com.routemesh.contracts.pricing.RedeemQuoteRequest;
import com.routemesh.contracts.shipment.CreateShipmentRequest;
import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.ShipmentStatus;
import com.routemesh.contracts.shipment.TransitionStatusRequest;
import com.routemesh.workflowservice.workflow.PaymentInfo;
import com.routemesh.workflowservice.workflow.QuoteInfo;
import com.routemesh.workflowservice.workflow.ShipmentWorkflowInput;

import io.grpc.StatusRuntimeException;
import io.temporal.activity.Activity;

@Component
public class ShipmentActivitiesImpl implements ShipmentActivities {

    private static final String CURRENCY = "TRY";
    private static final String PAYMENT_PREFIX = "PAYMENT_STATUS_";
    private static final String CLEARING = "ASSET:PSP_CLEARING";
    private static final String ESCROW = "LIABILITY:ESCROW";

    private final PricingServiceGrpc.PricingServiceBlockingStub pricing;
    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments;
    private final PaymentServiceGrpc.PaymentServiceBlockingStub payments;
    private final LedgerServiceGrpc.LedgerServiceBlockingStub ledger;

    public ShipmentActivitiesImpl(PricingServiceGrpc.PricingServiceBlockingStub pricing,
            ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments,
            PaymentServiceGrpc.PaymentServiceBlockingStub payments,
            LedgerServiceGrpc.LedgerServiceBlockingStub ledger) {
        this.pricing = pricing;
        this.shipments = shipments;
        this.payments = payments;
        this.ledger = ledger;
    }

    @Override
    public QuoteInfo redeemQuote(UUID quoteId, UUID merchantId, UUID shipmentId) {
        Quote quote = RpcErrors.call(() -> pricing.getQuote(
                GetQuoteRequest.newBuilder().setQuoteId(quoteId.toString()).build()));
        if (!quote.getMerchantId().equals(merchantId.toString())) {
            throw RpcErrors.rejected("quote not found");
        }
        Quote redeemed = RpcErrors.call(() -> pricing.redeemQuote(RedeemQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .setRedemptionKey(key("redeem"))
                .setShipmentId(shipmentId.toString())
                .build())).getQuote();
        return new QuoteInfo(redeemed.getFinalAmount().getAmountMinor(), redeemed.getBasePrice().getAmountMinor(),
                redeemed.getDestination().getLatitude(), redeemed.getDestination().getLongitude(),
                redeemed.getWeightGrams());
    }

    @Override
    public void createShipment(ShipmentWorkflowInput input, QuoteInfo quote) {
        CreateShipmentRequest.Builder builder = CreateShipmentRequest.newBuilder();
        if (quote.weightGrams() > 0) {
            builder.setWeightGrams(quote.weightGrams());
        }
        RpcErrors.call(() -> shipments.createShipment(builder
                .setIdempotencyKey(key("create"))
                .setShipmentId(input.shipmentId().toString())
                .setMerchantId(input.merchantId().toString())
                .setRecipientName(input.recipientName())
                .setRecipientPhone(input.recipientPhone())
                .setDeliveryAddress(input.deliveryAddress())
                .setDeliveryLocation(com.routemesh.contracts.common.LatLong.newBuilder()
                        .setLatitude(quote.destinationLatitude()).setLongitude(quote.destinationLongitude()))
                .setQuoteId(input.quoteId().toString())
                .setFinalAmount(money(quote.finalAmountMinor()))
                .setBasePrice(money(quote.basePriceMinor()))
                .build()));
    }

    @Override
    public PaymentInfo createPaymentIntent(UUID shipmentId, long amountMinor) {
        return info(RpcErrors.call(() -> payments.createPaymentIntent(CreatePaymentIntentRequest.newBuilder()
                .setIdempotencyKey(key("intent"))
                .setShipmentId(shipmentId.toString())
                .setAmount(money(amountMinor))
                .build())));
    }

    @Override
    public PaymentInfo getPayment(String paymentId) {
        return info(readPayment(paymentId), false);
    }

    @Override
    public PaymentInfo expirePayment(String paymentId) {
        return info(RpcErrors.call(() -> payments.expirePayment(
                ExpirePaymentRequest.newBuilder().setPaymentId(paymentId).build())));
    }

    @Override
    public PaymentInfo capturePayment(String paymentId) {
        return info(RpcErrors.call(() -> payments.capturePayment(CapturePaymentRequest.newBuilder()
                .setPaymentId(paymentId)
                .setIdempotencyKey(key("capture"))
                .build())));
    }

    @Override
    public void postEscrow(UUID shipmentId, long amountMinor) {
        escrowTransaction(shipmentId, amountMinor);
    }

    @Override
    public void markPaid(UUID shipmentId) {
        transition(shipmentId, ShipmentStatus.SHIPMENT_STATUS_PAID, key("paid"), null);
    }

    @Override
    public void cancelShipment(UUID shipmentId, String reason) {
        com.routemesh.contracts.shipment.Shipment shipment;
        try {
            shipment = shipments.getShipment(GetShipmentRequest.newBuilder()
                    .setShipmentId(shipmentId.toString()).build());
        } catch (StatusRuntimeException e) {
            if (RpcErrors.isNotFound(e)) {
                return;
            }
            throw RpcErrors.translate(e);
        }
        if (shipment.getStatus() == ShipmentStatus.SHIPMENT_STATUS_CANCELLED) {
            return;
        }
        if (shipment.getStatus() != ShipmentStatus.SHIPMENT_STATUS_PENDING_PAYMENT) {
            throw RpcErrors.rejected("shipment is " + shipment.getStatus() + " and cannot be cancelled by the "
                    + "workflow, manual intervention is required");
        }
        transition(shipmentId, ShipmentStatus.SHIPMENT_STATUS_CANCELLED, key("cancel"), reason);
    }

    @Override
    public void releasePayment(String paymentId, long amountMinor, String reason) {
        PaymentIntent payment = readPayment(paymentId);
        switch (payment.getStatus()) {
            case PAYMENT_STATUS_CREATED -> RpcErrors.call(() -> payments.expirePayment(
                    ExpirePaymentRequest.newBuilder().setPaymentId(paymentId).build()));
            case PAYMENT_STATUS_AUTHORIZED -> RpcErrors.call(() -> payments.voidPayment(VoidPaymentRequest
                    .newBuilder().setPaymentId(paymentId).setIdempotencyKey(key("void")).setReason(reason)
                    .build()));
            case PAYMENT_STATUS_CAPTURED, PAYMENT_STATUS_PARTIALLY_REFUNDED -> {
                long remaining = payment.getAmount().getAmountMinor() - payment.getRefundedAmount().getAmountMinor();
                if (remaining > 0) {
                    RpcErrors.call(() -> payments.refundPayment(RefundPaymentRequest.newBuilder()
                            .setPaymentId(paymentId).setIdempotencyKey(key("refund")).setAmount(money(remaining))
                            .setReason(reason).build()));
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void reverseEscrow(UUID shipmentId, long amountMinor) {
        String original = escrowTransaction(shipmentId, amountMinor);
        RpcErrors.call(() -> ledger.postTransaction(PostTransactionRequest.newBuilder()
                .setIdempotencyKey(key("escrow-reversal"))
                .setTransactionType("ESCROW_REVERSAL")
                .setShipmentId(shipmentId.toString())
                .setReversalOf(original)
                .addEntries(entry(CLEARING, EntryDirection.ENTRY_DIRECTION_CREDIT, amountMinor))
                .addEntries(entry(ESCROW, EntryDirection.ENTRY_DIRECTION_DEBIT, amountMinor))
                .build()));
    }

    private String escrowTransaction(UUID shipmentId, long amountMinor) {
        return RpcErrors.call(() -> ledger.postTransaction(PostTransactionRequest.newBuilder()
                .setIdempotencyKey(key("escrow"))
                .setTransactionType("ESCROW_FUNDED")
                .setShipmentId(shipmentId.toString())
                .addEntries(entry(CLEARING, EntryDirection.ENTRY_DIRECTION_DEBIT, amountMinor))
                .addEntries(entry(ESCROW, EntryDirection.ENTRY_DIRECTION_CREDIT, amountMinor))
                .build())).getTransactionId();
    }

    private void transition(UUID shipmentId, ShipmentStatus to, String transitionKey, String reason) {
        TransitionStatusRequest.Builder request = TransitionStatusRequest.newBuilder()
                .setShipmentId(shipmentId.toString())
                .setToStatus(to)
                .setTransitionKey(transitionKey)
                .setExpectedFromStatus(ShipmentStatus.SHIPMENT_STATUS_PENDING_PAYMENT);
        if (reason != null) {
            request.setReason(reason);
        }
        RpcErrors.call(() -> shipments.transitionStatus(request.build()));
    }

    private PaymentIntent readPayment(String paymentId) {
        return RpcErrors.call(() -> payments.getPayment(GetPaymentRequest.newBuilder().setPaymentId(paymentId).build()));
    }

    private static PaymentInfo info(PaymentCommandResponse response) {
        return info(response.getPayment(), response.getPending());
    }

    private static PaymentInfo info(PaymentIntent payment, boolean pending) {
        return new PaymentInfo(payment.getId(), payment.getStatus().name().substring(PAYMENT_PREFIX.length()),
                payment.getCheckoutUrl(), pending);
    }

    private static LedgerEntry entry(String account, EntryDirection direction, long amountMinor) {
        return LedgerEntry.newBuilder().setAccountCode(account).setDirection(direction)
                .setAmount(money(amountMinor)).build();
    }

    private static Money money(long amountMinor) {
        return Money.newBuilder().setAmountMinor(amountMinor).setCurrency(CURRENCY).build();
    }

    private static String key(String operation) {
        return Activity.getExecutionContext().getInfo().getWorkflowId() + ":" + operation;
    }
}
