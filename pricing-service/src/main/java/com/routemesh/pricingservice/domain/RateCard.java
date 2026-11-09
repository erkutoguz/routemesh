package com.routemesh.pricingservice.domain;

public record RateCard(
        String zoneCode,
        long baseFeeMinor,
        long perKmMinor,
        int includedWeightGrams,
        long perExtraKgMinor,
        long minFeeMinor) {
}
