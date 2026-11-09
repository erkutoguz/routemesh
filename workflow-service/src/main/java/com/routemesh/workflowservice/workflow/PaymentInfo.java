package com.routemesh.workflowservice.workflow;

public record PaymentInfo(
        String paymentId,
        String status,
        String checkoutUrl,
        boolean pending) {
}
