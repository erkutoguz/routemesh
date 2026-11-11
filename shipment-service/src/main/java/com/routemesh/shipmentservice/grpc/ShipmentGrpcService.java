package com.routemesh.shipmentservice.grpc;

import org.springframework.grpc.server.service.GrpcService;

import com.routemesh.contracts.shipment.CreateShipmentRequest;
import com.routemesh.contracts.shipment.CreateShipmentResponse;
import com.google.protobuf.Timestamp;
import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.IssueDeliveryOtpRequest;
import com.routemesh.contracts.shipment.IssueDeliveryOtpResponse;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.TransitionStatusRequest;
import com.routemesh.contracts.shipment.TransitionStatusResponse;
import com.routemesh.shipmentservice.domain.Milestones;
import com.routemesh.shipmentservice.domain.ShipmentResult;
import com.routemesh.shipmentservice.service.OtpService;
import com.routemesh.shipmentservice.service.ShipmentException;
import com.routemesh.shipmentservice.service.ShipmentService;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@GrpcService
public class ShipmentGrpcService extends ShipmentServiceGrpc.ShipmentServiceImplBase {

    private final ShipmentService shipmentService;
    private final OtpService otpService;

    public ShipmentGrpcService(ShipmentService shipmentService, OtpService otpService) {
        this.otpService = otpService;
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
            java.util.UUID id = ShipmentMapper.parseUuid(request.getShipmentId(), "shipment_id");
            Shipment.Builder shipment = ShipmentMapper.toProto(shipmentService.get(id)).toBuilder();
            Milestones milestones = shipmentService.milestones(id);
            if (milestones.warehouseReceivedAt() != null) {
                shipment.setWarehouseReceivedAt(ShipmentMapper.timestamp(milestones.warehouseReceivedAt()));
            }
            if (milestones.deliveredAt() != null) {
                shipment.setDeliveredAt(ShipmentMapper.timestamp(milestones.deliveredAt()));
            }
            observer.onNext(shipment.build());
            observer.onCompleted();
        } catch (ShipmentException e) {
            observer.onError(toStatus(e));
        }
    }

    @Override
    public void issueDeliveryOtp(IssueDeliveryOtpRequest request, StreamObserver<IssueDeliveryOtpResponse> observer) {
        try {
            OtpService.Issued issued = otpService.issue(ShipmentMapper.parseUuid(request.getShipmentId(), "shipment_id"));
            observer.onNext(IssueDeliveryOtpResponse.newBuilder()
                    .setCode(issued.code())
                    .setExpiresAt(Timestamp.newBuilder().setSeconds(issued.expiresAt().getEpochSecond())
                            .setNanos(issued.expiresAt().getNano()))
                    .build());
            observer.onCompleted();
        } catch (ShipmentException e) {
            observer.onError(toStatus(e));
        }
    }

    private static StatusRuntimeException toStatus(ShipmentException e) {
        Status status = switch (e.getReason()) {
            case INVALID_REQUEST, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA -> Status.INVALID_ARGUMENT;
            case NOT_FOUND -> Status.NOT_FOUND;
            case ILLEGAL_TRANSITION, POD_TOO_FAR, OTP_LOCKED -> Status.FAILED_PRECONDITION;
            case IDEMPOTENCY_CONFLICT -> Status.ALREADY_EXISTS;
            case OTP_INVALID -> Status.PERMISSION_DENIED;
            case STORAGE_UNAVAILABLE -> Status.UNAVAILABLE;
        };
        return status.withDescription(e.getMessage()).asRuntimeException();
    }
}
