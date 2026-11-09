package com.routemesh.workflowservice.workflow;

public record ShipmentWorkflowResult(String status, String reason, String paymentId) {

    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";
}
