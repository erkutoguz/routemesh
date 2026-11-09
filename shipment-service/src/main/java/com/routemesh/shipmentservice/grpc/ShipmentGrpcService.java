package com.routemesh.shipmentservice.grpc;

import org.springframework.grpc.server.service.GrpcService;

import com.routemesh.contracts.shipment.CreateShipmentRequest;
import com.routemesh.contracts.shipment.CreateShipmentResponse;
import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.TransitionStatusRequest;
import com.routemesh.contracts.shipment.TransitionStatusResponse;
import com.routemesh.shipmentservice.domain.ShipmentResult;
import com.routemesh.shipmentservice.service.ShipmentException;
import com.routemesh.shipmentservice.service.ShipmentService;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@GrpcService
public class ShipmentGrpcService extends ShipmentServiceGrpc.ShipmentServiceImplBase {

    private final ShipmentService shipmentService;

    public ShipmentGrpcService(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @Override
    public void createShipment(CreateShipmentRequest request, StreamObserver<CreateShipmentResponse> observer) {
        try {
            ShipmentResult result = shipmentService.create(ShipmentMapper.toCommand(request));
            observer.onNext(CreateShipmentResponse.newBuilder()
                    .setShipment(ShipmentMapper.toProto(result.shipment()))
                    .setReplayed(result.replayed())
                    .build());
            observer.onCompleted();
        } catch (ShipmentException e) {
            observer.onError(toStatus(e));
        }
    }

    @Override
    public void transitionStatus(TransitionStatusRequest request, StreamObserver<TransitionStatusResponse> observer) {
        try {
            ShipmentResult result = shipmentService.transition(ShipmentMapper.toCommand(request));
            observer.onNext(TransitionStatusResponse.newBuilder()
                    .setShipment(ShipmentMapper.toProto(result.shipment()))
                    .setReplayed(result.replayed())
                    .build());
            observer.onCompleted();
        } catch (ShipmentException e) {
            observer.onError(toStatus(e));
        }
    }

    @Override
    public void getShipment(GetShipmentRequest request, StreamObserver<Shipment> observer) {
        try {
            observer.onNext(ShipmentMapper.toProto(
                    shipmentService.get(ShipmentMapper.parseUuid(request.getShipmentId(), "shipment_id"))));
            observer.onCompleted();
        } catch (ShipmentException e) {
            observer.onError(toStatus(e));
        }
    }

    private static StatusRuntimeException toStatus(ShipmentException e) {
        Status status = switch (e.getReason()) {
            case INVALID_REQUEST -> Status.INVALID_ARGUMENT;
            case NOT_FOUND -> Status.NOT_FOUND;
            case ILLEGAL_TRANSITION -> Status.FAILED_PRECONDITION;
            case IDEMPOTENCY_CONFLICT -> Status.ALREADY_EXISTS;
        };
        return status.withDescription(e.getMessage()).asRuntimeException();
    }
}
