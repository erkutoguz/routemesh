package com.routemesh.workflowservice.workflow;

public record QuoteInfo(
        long finalAmountMinor,
        long basePriceMinor,
        double destinationLatitude,
        double destinationLongitude,
        long weightGrams) {

    public QuoteInfo(long finalAmountMinor, long basePriceMinor, double destinationLatitude,
            double destinationLongitude) {
        this(finalAmountMinor, basePriceMinor, destinationLatitude, destinationLongitude, 0);
    }
}
