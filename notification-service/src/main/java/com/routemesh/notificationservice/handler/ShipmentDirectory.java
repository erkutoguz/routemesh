package com.routemesh.notificationservice.handler;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@Component
public class ShipmentDirectory {

    private static final Set<Status.Code> PERMANENT = Set.of(Status.Code.NOT_FOUND, Status.Code.INVALID_ARGUMENT,
            Status.Code.FAILED_PRECONDITION, Status.Code.PERMISSION_DENIED);

    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments;

    public ShipmentDirectory(ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments) {
        this.shipments = shipments;
    }

    public Shipment get(UUID shipmentId) {
        try {
            return shipments.getShipment(GetShipmentRequest.newBuilder().setShipmentId(shipmentId.toString()).build());
        } catch (StatusRuntimeException e) {
            throw classify(e, "shipment " + shipmentId);
        }
    }

    static RuntimeException classify(StatusRuntimeException e, String what) {
        if (PERMANENT.contains(e.getStatus().getCode())) {
            return new NonRetryableEventException(what + " lookup failed: " + e.getStatus().getCode(), e);
        }
        return e;
    }
}
