package com.routemesh.pricingservice.service;

import com.routemesh.pricingservice.domain.RateCard;

public final class PriceCalculator {

    private static final long BPS_DIVISOR = 10_000;
    private static final long GRAMS_PER_KG = 1_000;
    private static final long METERS_PER_KM = 1_000;

    public record Price(long basePriceMinor, long finalAmountMinor) {
    }

    private PriceCalculator() {
    }

    public static long billableKilometers(long distanceMeters) {
        return Math.max(1, (distanceMeters + METERS_PER_KM - 1) / METERS_PER_KM);
    }

    public static long extraKilograms(int weightGrams, int includedWeightGrams) {
        long extraGrams = Math.max(0, (long) weightGrams - includedWeightGrams);
        return (extraGrams + GRAMS_PER_KG - 1) / GRAMS_PER_KG;
    }

    public static Price calculate(RateCard rate, long distanceMeters, int weightGrams,
                                  int serviceMultiplierBps, int surgeBps) {
        long raw = Math.addExact(
                Math.addExact(rate.baseFeeMinor(),
                        Math.multiplyExact(billableKilometers(distanceMeters), rate.perKmMinor())),
                Math.multiplyExact(extraKilograms(weightGrams, rate.includedWeightGrams()), rate.perExtraKgMinor()));
        raw = Math.max(raw, rate.minFeeMinor());
        long base = Math.multiplyExact(raw, (long) serviceMultiplierBps) / BPS_DIVISOR;
        long finalAmount = Math.multiplyExact(base, (long) surgeBps) / BPS_DIVISOR;
        return new Price(base, finalAmount);
    }
}
