package com.routemesh.fleetservice.grpc;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.grpc.server.service.GrpcService;

import com.google.protobuf.Timestamp;
import com.routemesh.contracts.fleet.CreateOfferRequest;
import com.routemesh.contracts.fleet.CreateOfferResponse;
import com.routemesh.contracts.fleet.ExpireOfferRequest;
import com.routemesh.contracts.fleet.FleetServiceGrpc;
import com.routemesh.contracts.fleet.GetOfferRequest;
import com.routemesh.contracts.fleet.ReleaseCourierRequest;
import com.routemesh.contracts.fleet.ReleaseCourierResponse;
import com.routemesh.fleetservice.domain.CreateOfferCommand;
import com.routemesh.fleetservice.domain.Offer;
import com.routemesh.fleetservice.domain.OfferResult;
import com.routemesh.fleetservice.service.FleetException;
import com.routemesh.fleetservice.service.FleetException.Reason;
import com.routemesh.fleetservice.service.FleetService;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@GrpcService
public class FleetGrpcService extends FleetServiceGrpc.FleetServiceImplBase {

    private static final String STATUS_PREFIX = "OFFER_STATUS_";

    private final FleetService fleetService;

    public FleetGrpcService(FleetService fleetService) {
        this.fleetService = fleetService;
    }

    @Override
    public void createOffer(CreateOfferRequest request, StreamObserver<CreateOfferResponse> observer) {
        reply(observer, () -> {
            OfferResult result = fleetService.createOffer(new CreateOfferCommand(
                    parseUuid(request.getOfferId(), "offer_id"),
                    parseUuid(request.getShipmentId(), "shipment_id"),
                    parseUuid(request.getCourierId(), "courier_id"),
                    request.getWeightGrams(),
                    request.getTtlSeconds()));
            return CreateOfferResponse.newBuilder()
                    .setOffer(toProto(result.offer()))
                    .setReplayed(result.replayed())
                    .build();
        });
    }

    @Override
    public void getOffer(GetOfferRequest request, StreamObserver<com.routemesh.contracts.fleet.Offer> observer) {
        reply(observer, () -> toProto(fleetService.getOffer(parseUuid(request.getOfferId(), "offer_id"))));
    }

    @Override
    public void expireOffer(ExpireOfferRequest request, StreamObserver<com.routemesh.contracts.fleet.Offer> observer) {
        reply(observer, () -> toProto(fleetService.expireOffer(parseUuid(request.getOfferId(), "offer_id"))));
    }

    @Override
    public void releaseCourier(ReleaseCourierRequest request, StreamObserver<ReleaseCourierResponse> observer) {
        reply(observer, () -> ReleaseCourierResponse.newBuilder()
                .setReleased(fleetService.releaseCourier(
                        parseUuid(request.getCourierId(), "courier_id"),
                        parseUuid(request.getShipmentId(), "shipment_id")))
                .build());
    }

    private static <T> void reply(StreamObserver<T> observer, Supplier<T> action) {
        try {
            observer.onNext(action.get());
            observer.onCompleted();
        } catch (FleetException e) {
            observer.onError(toStatus(e));
        }
    }

    private static StatusRuntimeException toStatus(FleetException e) {
        Status status = switch (e.getReason()) {
            case INVALID_REQUEST -> Status.INVALID_ARGUMENT;
            case NOT_FOUND -> Status.NOT_FOUND;
            case PRECONDITION_FAILED -> Status.FAILED_PRECONDITION;
            case CONFLICT -> Status.ALREADY_EXISTS;
            case UNAVAILABLE -> Status.UNAVAILABLE;
        };
        return status.withDescription(e.getMessage()).asRuntimeException();
    }

    private static UUID parseUuid(String value, String field) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return uuid;
        } catch (IllegalArgumentException e) {
            throw new FleetException(Reason.INVALID_REQUEST, field + " must be a valid lower-case UUID");
        }
    }

    static com.routemesh.contracts.fleet.Offer toProto(Offer offer) {
        com.routemesh.contracts.fleet.Offer.Builder builder = com.routemesh.contracts.fleet.Offer.newBuilder()
                .setOfferId(offer.offerId().toString())
                .setShipmentId(offer.shipmentId().toString())
                .setCourierId(offer.courierId().toString())
                .setStatus(com.routemesh.contracts.fleet.OfferStatus.valueOf(STATUS_PREFIX + offer.status().name()))
                .setWeightGrams(offer.weightGrams())
                .setFenceToken(offer.fenceToken())
                .setExpiresAt(timestamp(offer.expiresAt()))
                .setCreatedAt(timestamp(offer.createdAt()));
        if (offer.decidedAt() != null) {
            builder.setDecidedAt(timestamp(offer.decidedAt()));
        }
        return builder.build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}
