package com.routemesh.pricingservice.rest;

import java.time.Instant;
import java.util.UUID;

import com.routemesh.pricingservice.domain.Quote;

record QuoteResponse(UUID id, Point origin, Point destination, String zoneCode, String serviceLevel, int weightGrams,
                     long distanceMeters, Money basePrice, int surgeBps, Money finalAmount, int slaMinutes,
                     String status, Instant createdAt, Instant expiresAt, boolean replayed) {

    record Point(double latitude, double longitude) {
    }

    record Money(long amountMinor, String currency) {
    }

    static QuoteResponse from(Quote quote, boolean replayed) {
        return new QuoteResponse(quote.id(),
                new Point(quote.originLatitude(), quote.originLongitude()),
                new Point(quote.destinationLatitude(), quote.destinationLongitude()),
                quote.zoneCode(), quote.serviceLevel().name(), quote.weightGrams(), quote.distanceMeters(),
                new Money(quote.basePriceMinor(), quote.currency()), quote.surgeBps(),
                new Money(quote.finalAmountMinor(), quote.currency()), quote.slaMinutes(), quote.status().name(),
                quote.createdAt(), quote.expiresAt(), replayed);
    }
}
