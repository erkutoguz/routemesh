package com.routemesh.workflowservice.workflow;

public record CheckoutInfo(String paymentId, String checkoutUrl, boolean paid) {

    public CheckoutInfo(String paymentId, String checkoutUrl) {
        this(paymentId, checkoutUrl, false);
    }
}
