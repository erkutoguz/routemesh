package com.routemesh.fleetservice.rest;

import java.time.Instant;
import java.util.UUID;

import com.routemesh.fleetservice.domain.Offer;

record OfferResponse(UUID offerId, UUID shipmentId, String status, int weightGrams, Instant expiresAt,
                     Instant createdAt) {

    static OfferResponse from(Offer offer) {
        return new OfferResponse(offer.offerId(), offer.shipmentId(), offer.status().name(), offer.weightGrams(),
                offer.expiresAt(), offer.createdAt());
    }
}
