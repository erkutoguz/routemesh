package com.routemesh.workflowservice.activity;

import java.util.List;
import java.util.UUID;

import com.routemesh.workflowservice.workflow.DispatchTarget;
import com.routemesh.workflowservice.workflow.OfferInfo;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface DispatchActivities {

    DispatchTarget loadShipment(UUID shipmentId);

    /** Depoya göre yarıçap içindeki kuryeler, yakından uzağa. */
    List<UUID> findCouriers(double radiusMeters);

    OfferInfo createOffer(UUID offerId, UUID shipmentId, UUID courierId, long weightGrams, int ttlSeconds);

    OfferInfo getOffer(UUID offerId);

    OfferInfo expireOffer(UUID offerId);

    void assignShipment(UUID shipmentId, UUID courierId);

    void holdShipment(UUID shipmentId, String reason);

    void releaseCourier(UUID courierId, UUID shipmentId);
}
