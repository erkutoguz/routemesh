package com.routemesh.locationservice.grpc;

import java.util.List;

import org.springframework.grpc.server.service.GrpcService;

import com.routemesh.contracts.location.CourierDistance;
import com.routemesh.contracts.location.CourierLocationTelemetry;
import com.routemesh.contracts.location.FindNearestRequest;
import com.routemesh.contracts.location.FindNearestResponse;
import com.routemesh.contracts.location.LocationServiceGrpc;
import com.routemesh.contracts.location.StreamCourierLocationsResponse;
import com.routemesh.locationservice.service.CourierLocationService;
import com.routemesh.locationservice.service.InvalidRequestException;
import com.routemesh.locationservice.store.NearbyCourier;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@GrpcService
public class LocationGrpcService extends LocationServiceGrpc.LocationServiceImplBase {

    private final CourierLocationService service;

    public LocationGrpcService(CourierLocationService service) {
        this.service = service;
    }

    @Override
    public StreamObserver<CourierLocationTelemetry> streamCourierLocations(
            StreamObserver<StreamCourierLocationsResponse> responseObserver) {

        return new StreamObserver<>() {
            private long accepted;
            private long rejectedStale;
            private boolean failed;

            @Override
            public void onNext(CourierLocationTelemetry telemetry) {
                if (failed) {
                    return;
                }
                try {
                    if (!telemetry.hasLocation()) {
                        throw new InvalidRequestException("location is required");
                    }
                    boolean isAccepted = service.ingest(
                            telemetry.getCourierId(),
                            telemetry.getSessionEpoch(),
                            telemetry.getSequenceNumber(),
                            telemetry.getLocation().getLatitude(),
                            telemetry.getLocation().getLongitude());
                    if (isAccepted) {
                        accepted++;
                    } else {
                        rejectedStale++;
                    }
                } catch (InvalidRequestException e) {
                    failed = true;
                    responseObserver.onError(invalidArgument(e));
                }
            }

            @Override
            public void onError(Throwable t) {
                // İstemci akışı iptal etti ya da koptu, cevap gönderilmez.
            }

            @Override
            public void onCompleted() {
                if (failed) {
                    return;
                }
                responseObserver.onNext(StreamCourierLocationsResponse.newBuilder()
                        .setAccepted(accepted)
                        .setRejectedStale(rejectedStale)
                        .build());
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void findNearestCouriers(FindNearestRequest request,
                                    StreamObserver<FindNearestResponse> responseObserver) {
        try {
            if (!request.hasTargetLocation()) {
                throw new InvalidRequestException("target_location is required");
            }
            List<NearbyCourier> couriers = service.findNearest(
                    request.getTargetLocation().getLatitude(),
                    request.getTargetLocation().getLongitude(),
                    request.getRadiusMeters(),
                    request.getLimit());

            FindNearestResponse.Builder response = FindNearestResponse.newBuilder();
            for (NearbyCourier courier : couriers) {
                response.addCouriers(CourierDistance.newBuilder()
                        .setCourierId(courier.courierId())
                        .setDistanceMeters(courier.distanceMeters()));
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (InvalidRequestException e) {
            responseObserver.onError(invalidArgument(e));
        }
    }

    private static StatusRuntimeException invalidArgument(InvalidRequestException e) {
        return Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException();
    }
}
