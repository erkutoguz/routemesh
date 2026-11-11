package com.routemesh.workflowservice.activity;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.routemesh.contracts.common.Money;
import com.routemesh.contracts.ledger.EntryDirection;
import com.routemesh.contracts.ledger.GetBalanceRequest;
import com.routemesh.contracts.ledger.LedgerEntry;
import com.routemesh.contracts.ledger.LedgerServiceGrpc;
import com.routemesh.contracts.ledger.PostTransactionRequest;
import com.routemesh.contracts.payment.FindPaymentByShipmentRequest;
import com.routemesh.contracts.payment.PaymentIntent;
import com.routemesh.contracts.payment.PaymentServiceGrpc;
import com.routemesh.contracts.payment.RefundPaymentRequest;
import com.routemesh.contracts.pricing.GetQuoteRequest;
import com.routemesh.contracts.pricing.PricingServiceGrpc;
import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.ShipmentStatus;
import com.routemesh.workflowservice.settlement.SettlementActivities;
import com.routemesh.workflowservice.settlement.SettlementContext;
import com.routemesh.workflowservice.settlement.SettlementTemplates;

import io.temporal.activity.Activity;

@Component
public class SettlementActivitiesImpl implements SettlementActivities {

    private static final String CURRENCY = "TRY";

    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments;
    private final PricingServiceGrpc.PricingServiceBlockingStub pricing;
    private final LedgerServiceGrpc.LedgerServiceBlockingStub ledger;
    private final PaymentServiceGrpc.PaymentServiceBlockingStub payments;

    public SettlementActivitiesImpl(ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments,
            PricingServiceGrpc.PricingServiceBlockingStub pricing,
            LedgerServiceGrpc.LedgerServiceBlockingStub ledger,
            PaymentServiceGrpc.PaymentServiceBlockingStub payments) {
        this.shipments = shipments;
        this.pricing = pricing;
        this.ledger = ledger;
        this.payments = payments;
    }

    @Override
    public SettlementContext load(UUID shipmentId) {
        Shipment shipment = RpcErrors.call(() -> shipments.getShipment(
                GetShipmentRequest.newBuilder().setShipmentId(shipmentId.toString()).build()));
        if (shipment.getStatus() != ShipmentStatus.SHIPMENT_STATUS_DELIVERED) {
            throw RpcErrors.rejected("shipment is " + shipment.getStatus() + ", expected DELIVERED");
        }
        if (!shipment.hasCourierId() || !shipment.hasWarehouseReceivedAt() || !shipment.hasDeliveredAt()) {
            throw RpcErrors.rejected("delivered shipment has no courier or delivery milestones");
        }
        long amount = shipment.getFinalAmount().getAmountMinor();
        long escrow = RpcErrors.call(() -> ledger.getBalance(GetBalanceRequest.newBuilder()
                .setAccountCode(SettlementTemplates.ESCROW).setShipmentId(shipmentId.toString()).build()))
                .getBalance().getAmountMinor();
        if (escrow != amount && escrow != 0) {
            throw RpcErrors.rejected("escrow balance " + escrow + " does not match the shipment amount " + amount);
        }
        int slaMinutes = RpcErrors.call(() -> pricing.getQuote(
                GetQuoteRequest.newBuilder().setQuoteId(shipment.getQuoteId()).build())).getSlaMinutes();

        return new SettlementContext(shipmentId.toString(), shipment.getMerchantId(), shipment.getCourierId(), amount,
                escrow, millis(shipment.getWarehouseReceivedAt()), millis(shipment.getDeliveredAt()), slaMinutes);
    }

    @Override
    public void postLedger(UUID shipmentId, String transactionType, List<SettlementTemplates.Line> lines) {
        PostTransactionRequest.Builder request = PostTransactionRequest.newBuilder()
                .setIdempotencyKey(key(transactionType))
                .setTransactionType(transactionType)
                .setShipmentId(shipmentId.toString());
        for (SettlementTemplates.Line line : lines) {
            request.addEntries(LedgerEntry.newBuilder()
                    .setAccountCode(line.account())
                    .setDirection(line.debit() ? EntryDirection.ENTRY_DIRECTION_DEBIT
                            : EntryDirection.ENTRY_DIRECTION_CREDIT)
                    .setAmount(money(line.amountMinor())));
        }
        RpcErrors.call(() -> ledger.postTransaction(request.build()));
    }

    @Override
    public void refundPayment(UUID shipmentId, String transactionType, long amountMinor, String reason) {
        PaymentIntent payment = RpcErrors.call(() -> payments.findPaymentByShipment(
                FindPaymentByShipmentRequest.newBuilder().setShipmentId(shipmentId.toString()).build()));
        RpcErrors.call(() -> payments.refundPayment(RefundPaymentRequest.newBuilder()
                .setPaymentId(payment.getId())
                .setIdempotencyKey(key(transactionType) + ":refund")
                .setAmount(money(amountMinor))
                .setReason(reason)
                .build()));
    }

    private static long millis(com.google.protobuf.Timestamp timestamp) {
        return timestamp.getSeconds() * 1000 + timestamp.getNanos() / 1_000_000;
    }

    private static Money money(long amountMinor) {
        return Money.newBuilder().setAmountMinor(amountMinor).setCurrency(CURRENCY).build();
    }

    private static String key(String operation) {
        return Activity.getExecutionContext().getInfo().getWorkflowId() + ":" + operation;
    }
}
