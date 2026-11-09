package com.routemesh.pricingservice.grpc;

import java.time.Instant;
import java.util.UUID;

import com.google.protobuf.Timestamp;
import com.routemesh.contracts.common.LatLong;
import com.routemesh.contracts.common.Money;
import com.routemesh.contracts.pricing.CreateQuoteRequest;
import com.routemesh.contracts.pricing.RedeemQuoteRequest;
import com.routemesh.pricingservice.domain.CreateQuoteCommand;
import com.routemesh.pricingservice.domain.Quote;
import com.routemesh.pricingservice.domain.RedeemQuoteCommand;
import com.routemesh.pricingservice.domain.ServiceLevel;
import com.routemesh.pricingservice.service.PricingException;
import com.routemesh.pricingservice.service.PricingException.Reason;

final class QuoteMapper {

    private static final String SERVICE_LEVEL_PREFIX = "SERVICE_LEVEL_";
    private static final String STATUS_PREFIX = "QUOTE_STATUS_";

    private QuoteMapper() {
    }

    static CreateQuoteCommand toCommand(CreateQuoteRequest request) {
        if (!request.hasOrigin() || !request.hasDestination()) {
            throw invalid("origin and destination are required");
        }
        return new CreateQuoteCommand(
                request.getIdempotencyKey(),
                parseUuid(request.getMerchantId(), "merchant_id"),
                request.getOrigin().getLatitude(),
                request.getOrigin().getLongitude(),
                request.getDestination().getLatitude(),
                request.getDestination().getLongitude(),
                toServiceLevel(request.getServiceLevel()),
                request.getWeightGrams());
    }

    static RedeemQuoteCommand toCommand(RedeemQuoteRequest request) {
        return new RedeemQuoteCommand(
                parseUuid(request.getQuoteId(), "quote_id"),
                request.getRedemptionKey(),
                parseUuid(request.getShipmentId(), "shipment_id"));
    }

    static UUID parseUuid(String value, String field) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return uuid;
        } catch (IllegalArgumentException e) {
            throw invalid(field + " must be a valid lower-case UUID");
        }
    }

    static com.routemesh.contracts.pricing.Quote toProto(Quote quote) {
        com.routemesh.contracts.pricing.Quote.Builder builder = com.routemesh.contracts.pricing.Quote.newBuilder()
                .setId(quote.id().toString())
                .setMerchantId(quote.merchantId().toString())
                .setOrigin(LatLong.newBuilder().setLatitude(quote.originLatitude())
                        .setLongitude(quote.originLongitude()))
                .setDestination(LatLong.newBuilder().setLatitude(quote.destinationLatitude())
                        .setLongitude(quote.destinationLongitude()))
                .setZoneCode(quote.zoneCode())
                .setServiceLevel(com.routemesh.contracts.pricing.ServiceLevel
                        .valueOf(SERVICE_LEVEL_PREFIX + quote.serviceLevel().name()))
                .setWeightGrams(quote.weightGrams())
                .setDistanceMeters(quote.distanceMeters())
                .setBasePrice(Money.newBuilder().setAmountMinor(quote.basePriceMinor()).setCurrency(quote.currency()))
                .setSurgeBps(quote.surgeBps())
                .setFinalAmount(Money.newBuilder().setAmountMinor(quote.finalAmountMinor())
                        .setCurrency(quote.currency()))
                .setSlaMinutes(quote.slaMinutes())
                .setStatus(com.routemesh.contracts.pricing.QuoteStatus.valueOf(STATUS_PREFIX + quote.status().name()))
                .setCreatedAt(timestamp(quote.createdAt()))
                .setExpiresAt(timestamp(quote.expiresAt()));
        if (quote.redeemedShipmentId() != null) {
            builder.setRedeemedShipmentId(quote.redeemedShipmentId().toString());
        }
        return builder.build();
    }

    private static ServiceLevel toServiceLevel(com.routemesh.contracts.pricing.ServiceLevel level) {
        if (level == com.routemesh.contracts.pricing.ServiceLevel.SERVICE_LEVEL_UNSPECIFIED
                || level == com.routemesh.contracts.pricing.ServiceLevel.UNRECOGNIZED) {
            throw invalid("service_level is required");
        }
        return ServiceLevel.valueOf(level.name().substring(SERVICE_LEVEL_PREFIX.length()));
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static PricingException invalid(String message) {
        return new PricingException(Reason.INVALID_REQUEST, message);
    }
}
