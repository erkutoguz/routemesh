package com.routemesh.workflowservice.activity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.routemesh.contracts.common.LatLong;
import com.routemesh.contracts.fleet.CreateOfferRequest;
import com.routemesh.contracts.fleet.ExpireOfferRequest;
import com.routemesh.contracts.fleet.FleetServiceGrpc;
import com.routemesh.contracts.fleet.GetOfferRequest;
import com.routemesh.contracts.fleet.Offer;
import com.routemesh.contracts.fleet.ReleaseCourierRequest;
import com.routemesh.contracts.location.CourierDistance;
import com.routemesh.contracts.location.FindNearestRequest;
import com.routemesh.contracts.location.LocationServiceGrpc;
import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.ShipmentStatus;
import com.routemesh.contracts.shipment.TransitionStatusRequest;
import com.routemesh.workflowservice.config.DispatchProperties;
import com.routemesh.workflowservice.workflow.DispatchTarget;
import com.routemesh.workflowservice.workflow.OfferInfo;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.Activity;

@Component
public class DispatchActivitiesImpl implements DispatchActivities {

    private static final String SHIPMENT_PREFIX = "SHIPMENT_STATUS_";
    private static final String OFFER_PREFIX = "OFFER_STATUS_";

    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments;
    private final LocationServiceGrpc.LocationServiceBlockingStub locations;
    private final FleetServiceGrpc.FleetServiceBlockingStub fleet;
    private final DispatchProperties properties;

    public DispatchActivitiesImpl(ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments,
            LocationServiceGrpc.LocationServiceBlockingStub locations,
            FleetServiceGrpc.FleetServiceBlockingStub fleet, DispatchProperties properties) {
        this.shipments = shipments;
        this.locations = locations;
        this.fleet = fleet;
        this.properties = properties;
    }

    @Override
    public DispatchTarget loadShipment(UUID shipmentId) {
        com.routemesh.contracts.shipment.Shipment shipment = RpcErrors.call(() -> shipments.getShipment(
                GetShipmentRequest.newBuilder().setShipmentId(shipmentId.toString()).build()));
        long weight = shipment.hasWeightGrams() ? shipment.getWeightGrams() : properties.defaultWeightGrams();
        return new DispatchTarget(shipment.getStatus().name().substring(SHIPMENT_PREFIX.length()), weight);
    }

    @Override
    public List<UUID> findCouriers(double radiusMeters) {
        List<CourierDistance> found = RpcErrors.call(() -> locations.findNearestCouriers(FindNearestRequest
                .newBuilder()
                .setTargetLocation(LatLong.newBuilder()
                        .setLatitude(properties.depotLatitude()).setLongitude(properties.depotLongitude()))
                .setRadiusMeters(radiusMeters)
                .setLimit(properties.candidateLimit())
                .build())).getCouriersList();
        List<UUID> ids = new ArrayList<>();
        for (CourierDistance courier : found) {
            try {
                ids.add(UUID.fromString(courier.getCourierId()));
            } catch (IllegalArgumentException e) {
                // kurye kimliği UUID değilse fleet-service de tanımaz; atla
            }
        }
        return ids;
    }

    @Override
    public OfferInfo createOffer(UUID offerId, UUID shipmentId, UUID courierId, long weightGrams, int ttlSeconds) {
        try {
            return offer(fleet.createOffer(CreateOfferRequest.newBuilder()
                    .setOfferId(offerId.toString())
                    .setShipmentId(shipmentId.toString())
                    .setCourierId(courierId.toString())
                    .setWeightGrams(weightGrams)
                    .setTtlSeconds(ttlSeconds)
                    .build()).getOffer());
        } catch (StatusRuntimeException e) {
            // kurye uygun değil (boşta değil, kapasite yetmiyor, açık teklifi var): sıradaki kurye
            if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                return new OfferInfo(OfferInfo.REFUSED);
            }
            throw RpcErrors.translate(e);
        }
    }

    @Override
    public OfferInfo getOffer(UUID offerId) {
        return offer(RpcErrors.call(() -> fleet.getOffer(
                GetOfferRequest.newBuilder().setOfferId(offerId.toString()).build())));
    }

    @Override
    public OfferInfo expireOffer(UUID offerId) {
        return offer(RpcErrors.call(() -> fleet.expireOffer(
                ExpireOfferRequest.newBuilder().setOfferId(offerId.toString()).build())));
    }

    @Override
    public void assignShipment(UUID shipmentId, UUID courierId) {
        RpcErrors.call(() -> shipments.transitionStatus(TransitionStatusRequest.newBuilder()
                .setShipmentId(shipmentId.toString())
                .setToStatus(ShipmentStatus.SHIPMENT_STATUS_ASSIGNED)
                .setTransitionKey(key("assign"))
                .setCourierId(courierId.toString())
                .setExpectedFromStatus(ShipmentStatus.SHIPMENT_STATUS_READY_FOR_DISPATCH)
                .build()));
    }

    @Override
    public void holdShipment(UUID shipmentId, String reason) {
        RpcErrors.call(() -> shipments.transitionStatus(TransitionStatusRequest.newBuilder()
                .setShipmentId(shipmentId.toString())
                .setToStatus(ShipmentStatus.SHIPMENT_STATUS_ON_HOLD)
                .setTransitionKey(key("hold"))
                .setReason(reason)
                .setExpectedFromStatus(ShipmentStatus.SHIPMENT_STATUS_READY_FOR_DISPATCH)
                .build()));
    }

    @Override
    public void releaseCourier(UUID courierId, UUID shipmentId) {
        RpcErrors.call(() -> fleet.releaseCourier(ReleaseCourierRequest.newBuilder()
                .setCourierId(courierId.toString())
                .setShipmentId(shipmentId.toString())
                .build()));
    }

    private static OfferInfo offer(Offer offer) {
        return new OfferInfo(offer.getStatus().name().substring(OFFER_PREFIX.length()));
    }

    private static String key(String operation) {
        return Activity.getExecutionContext().getInfo().getWorkflowId() + ":" + operation;
    }
}
