package com.routemesh.shipmentservice.grpc;

import java.time.Instant;
import java.util.UUID;

import com.google.protobuf.Timestamp;
import com.routemesh.contracts.common.LatLong;
import com.routemesh.contracts.common.Money;
import com.routemesh.contracts.shipment.CreateShipmentRequest;
import com.routemesh.contracts.shipment.TransitionStatusRequest;
import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.CreateShipmentCommand;
import com.routemesh.shipmentservice.domain.Shipment;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.service.ShipmentException;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;

final class ShipmentMapper {

    private static final String STATUS_PREFIX = "SHIPMENT_STATUS_";

    private ShipmentMapper() {
    }

    static CreateShipmentCommand toCommand(CreateShipmentRequest request) {
        if (!request.hasDeliveryLocation()) {
            throw invalid("delivery_location is required");
        }
        if (!request.hasFinalAmount() || !request.hasBasePrice()) {
            throw invalid("final_amount and base_price are required");
        }
        if (!request.getFinalAmount().getCurrency().equals(request.getBasePrice().getCurrency())) {
            throw invalid("final_amount and base_price must use the same currency");
        }
        return new CreateShipmentCommand(
                request.getIdempotencyKey(),
                parseUuid(request.getMerchantId(), "merchant_id"),
                request.getRecipientName(),
                request.getRecipientPhone(),
                request.getDeliveryAddress(),
                request.getDeliveryLocation().getLatitude(),
                request.getDeliveryLocation().getLongitude(),
                parseUuid(request.getQuoteId(), "quote_id"),
                request.getFinalAmount().getAmountMinor(),
                request.getBasePrice().getAmountMinor(),
                request.getFinalAmount().getCurrency());
    }

    static TransitionCommand toCommand(TransitionStatusRequest request) {
        return new TransitionCommand(
                parseUuid(request.getShipmentId(), "shipment_id"),
                toStatus(request.getToStatus(), "to_status"),
                Actor.SYSTEM,
                request.getTransitionKey(),
                request.getReason().isBlank() ? null : request.getReason(),
                request.hasCourierId() ? parseUuid(request.getCourierId(), "courier_id") : null,
                request.hasExpectedFromStatus() ? toStatus(request.getExpectedFromStatus(), "expected_from_status")
                        : null);
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

    static com.routemesh.contracts.shipment.Shipment toProto(Shipment shipment) {
        com.routemesh.contracts.shipment.Shipment.Builder builder =
                com.routemesh.contracts.shipment.Shipment.newBuilder()
                        .setId(shipment.id().toString())
                        .setMerchantId(shipment.merchantId().toString())
                        .setStatus(com.routemesh.contracts.shipment.ShipmentStatus
                                .valueOf(STATUS_PREFIX + shipment.status().name()))
                        .setVersion(shipment.version())
                        .setRecipientName(shipment.recipientName())
                        .setRecipientPhone(shipment.recipientPhone())
                        .setDeliveryAddress(shipment.deliveryAddress())
                        .setDeliveryLocation(LatLong.newBuilder()
                                .setLatitude(shipment.deliveryLatitude())
                                .setLongitude(shipment.deliveryLongitude()))
                        .setQuoteId(shipment.quoteId().toString())
                        .setFinalAmount(money(shipment.finalAmountMinor(), shipment.currency()))
                        .setBasePrice(money(shipment.basePriceMinor(), shipment.currency()))
                        .setCreatedAt(timestamp(shipment.createdAt()))
                        .setUpdatedAt(timestamp(shipment.updatedAt()));
        if (shipment.courierId() != null) {
            builder.setCourierId(shipment.courierId().toString());
        }
        return builder.build();
    }

    private static ShipmentStatus toStatus(com.routemesh.contracts.shipment.ShipmentStatus status, String field) {
        if (status == com.routemesh.contracts.shipment.ShipmentStatus.SHIPMENT_STATUS_UNSPECIFIED
                || status == com.routemesh.contracts.shipment.ShipmentStatus.UNRECOGNIZED) {
            throw invalid(field + " is required");
        }
        return ShipmentStatus.valueOf(status.name().substring(STATUS_PREFIX.length()));
    }

    private static Money.Builder money(long amountMinor, String currency) {
        return Money.newBuilder().setAmountMinor(amountMinor).setCurrency(currency);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static ShipmentException invalid(String message) {
        return new ShipmentException(Reason.INVALID_REQUEST, message);
    }
}
