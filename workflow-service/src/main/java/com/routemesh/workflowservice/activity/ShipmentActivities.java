package com.routemesh.workflowservice.activity;

import java.util.UUID;

import com.routemesh.workflowservice.workflow.PaymentInfo;
import com.routemesh.workflowservice.workflow.QuoteInfo;
import com.routemesh.workflowservice.workflow.ShipmentWorkflowInput;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ShipmentActivities {

    QuoteInfo redeemQuote(UUID quoteId, UUID merchantId, UUID shipmentId);

    void createShipment(ShipmentWorkflowInput input, QuoteInfo quote);

    PaymentInfo createPaymentIntent(UUID shipmentId, long amountMinor);

    PaymentInfo getPayment(String paymentId);

    PaymentInfo expirePayment(String paymentId);

    PaymentInfo capturePayment(String paymentId);

    void postEscrow(UUID shipmentId, long amountMinor);

    void markPaid(UUID shipmentId);

    void cancelShipment(UUID shipmentId, String reason);

    void releasePayment(String paymentId, long amountMinor, String reason);

    void reverseEscrow(UUID shipmentId, long amountMinor);
}
