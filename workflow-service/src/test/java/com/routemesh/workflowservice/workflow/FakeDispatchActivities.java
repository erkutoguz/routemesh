package com.routemesh.workflowservice.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.routemesh.workflowservice.activity.DispatchActivities;

import io.temporal.failure.ApplicationFailure;

/**
 * Dağıtım adımlarının test çifti. Her kurye için bir senaryo tanımlanır: kaç yoklamadan sonra kabul/ret
 * edeceği ya da hiç cevap vermeyeceği (süre dolumu).
 */
public class FakeDispatchActivities implements DispatchActivities {

    public enum Behavior { ACCEPT, REJECT, IGNORE, REFUSE }

    public record Scenario(Behavior behavior, int afterPolls) {
    }

    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final Map<UUID, Scenario> scenarios = new ConcurrentHashMap<>();
    public final Map<UUID, String> offerStatus = new ConcurrentHashMap<>();
    public final Map<UUID, UUID> offerCourier = new ConcurrentHashMap<>();
    public final Map<UUID, Integer> offerPolls = new ConcurrentHashMap<>();
    public final List<List<UUID>> scans = new CopyOnWriteArrayList<>();
    public final List<Double> radii = new CopyOnWriteArrayList<>();
    public final Deque<List<UUID>> scanResults = new ArrayDeque<>();

    public String shipmentStatus = "READY_FOR_DISPATCH";
    public long weightGrams = 2500;
    public RuntimeException assignFailure;
    public RuntimeException holdFailure;
    public List<UUID> defaultScan = new ArrayList<>();
    public List<UUID> farScan = new ArrayList<>();
    public UUID lastAssignedCourier;
    public String holdReason;

    @Override
    public DispatchTarget loadShipment(UUID shipmentId) {
        calls.add("load");
        return new DispatchTarget(shipmentStatus, weightGrams);
    }

    @Override
    public List<UUID> findCouriers(double radiusMeters) {
        calls.add("find");
        radii.add(radiusMeters);
        List<UUID> result;
        synchronized (scanResults) {
            result = radiusMeters == 5_000 && !scanResults.isEmpty() ? scanResults.poll() : null;
        }
        if (result == null) {
            result = radiusMeters == 5_000 ? defaultScan : farScan;
        }
        scans.add(result);
        return result;
    }

    @Override
    public OfferInfo createOffer(UUID offerId, UUID shipmentId, UUID courierId, long weight, int ttlSeconds) {
        calls.add("offer:" + courierId);
        Scenario scenario = scenarios.getOrDefault(courierId, new Scenario(Behavior.IGNORE, 0));
        if (scenario.behavior() == Behavior.REFUSE) {
            return new OfferInfo(OfferInfo.REFUSED);
        }
        offerStatus.put(offerId, OfferInfo.OFFERED);
        offerCourier.put(offerId, courierId);
        offerPolls.put(offerId, 0);
        return new OfferInfo(OfferInfo.OFFERED);
    }

    @Override
    public OfferInfo getOffer(UUID offerId) {
        calls.add("get");
        int polls = offerPolls.merge(offerId, 1, Integer::sum);
        Scenario scenario = scenarios.get(offerCourier.get(offerId));
        if (OfferInfo.OFFERED.equals(offerStatus.get(offerId)) && scenario != null && polls >= scenario.afterPolls()) {
            if (scenario.behavior() == Behavior.ACCEPT) {
                offerStatus.put(offerId, OfferInfo.ACCEPTED);
            } else if (scenario.behavior() == Behavior.REJECT) {
                offerStatus.put(offerId, OfferInfo.REJECTED);
            }
        }
        return new OfferInfo(offerStatus.get(offerId));
    }

    @Override
    public OfferInfo expireOffer(UUID offerId) {
        calls.add("expire");
        if (OfferInfo.OFFERED.equals(offerStatus.get(offerId))) {
            offerStatus.put(offerId, "EXPIRED");
        }
        return new OfferInfo(offerStatus.get(offerId));
    }

    @Override
    public void assignShipment(UUID shipmentId, UUID courierId) {
        calls.add("assign:" + courierId);
        if (assignFailure != null) {
            throw assignFailure;
        }
        lastAssignedCourier = courierId;
    }

    @Override
    public void holdShipment(UUID shipmentId, String reason) {
        calls.add("hold");
        if (holdFailure != null) {
            throw holdFailure;
        }
        holdReason = reason;
    }

    @Override
    public void releaseCourier(UUID courierId, UUID shipmentId) {
        calls.add("release:" + courierId);
    }

    public static ApplicationFailure rejected(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, "REJECTED");
    }
}
