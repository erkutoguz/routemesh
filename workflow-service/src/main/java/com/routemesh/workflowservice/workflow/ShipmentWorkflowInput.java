package com.routemesh.workflowservice.workflow;

import java.util.UUID;

public record ShipmentWorkflowInput(
        UUID shipmentId,
        UUID merchantId,
        UUID quoteId,
        String recipientName,
        String recipientPhone,
        String deliveryAddress) {
}
