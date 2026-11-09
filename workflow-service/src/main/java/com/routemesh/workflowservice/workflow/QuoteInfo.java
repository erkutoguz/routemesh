package com.routemesh.workflowservice.workflow;

public record QuoteInfo(
        long finalAmountMinor,
        long basePriceMinor,
        double destinationLatitude,
        double destinationLongitude) {
}
