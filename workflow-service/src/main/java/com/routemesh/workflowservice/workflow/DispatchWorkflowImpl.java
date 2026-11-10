package com.routemesh.workflowservice.workflow;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.routemesh.workflowservice.activity.DispatchActivities;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

/**
 * Hazır gönderiyi kuryelere sırayla teklif eder. Teklif kabul edilirse gönderi ASSIGNED olur; pencere
 * dolana kadar kimse kabul etmezse ON_HOLD'a alınır.
 */
public class DispatchWorkflowImpl implements DispatchWorkflow {

    static final Duration WINDOW = Duration.ofMinutes(10);
    static final Duration RESCAN = Duration.ofSeconds(30);
    static final Duration POLL_INITIAL = Duration.ofSeconds(2);
    static final Duration POLL_MAX = Duration.ofSeconds(5);
    static final int OFFER_TTL_SECONDS = 60;
    static final double[] RADII_METERS = {5_000, 15_000};
    static final String READY_FOR_DISPATCH = "READY_FOR_DISPATCH";
    static final String HOLD_REASON = "NO_COURIER_ACCEPTED";

    private final DispatchActivities activities = Workflow.newActivityStub(DispatchActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setScheduleToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2)
                            .setMaximumInterval(Duration.ofSeconds(15))
                            .build())
                    .build());

    /** Sonucu belirsiz bırakılamayacak adımlar: kesin bir ret gelene kadar süresiz denenir. */
    private final DispatchActivities critical = Workflow.newActivityStub(DispatchActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .build())
                    .build());

    private int offerSequence;

    @Override
    public DispatchResult dispatch(DispatchInput input) {
        DispatchTarget target = activities.loadShipment(input.shipmentId());
        if (!READY_FOR_DISPATCH.equals(target.status())) {
            return new DispatchResult(DispatchResult.SKIPPED, null, "shipment is " + target.status());
        }
        long deadline = Workflow.currentTimeMillis() + WINDOW.toMillis();
        Set<UUID> rejected = new HashSet<>();

        while (true) {
            Set<UUID> askedThisPass = new HashSet<>();
            for (double radius : RADII_METERS) {
                for (UUID courier : activities.findCouriers(radius)) {
                    if (rejected.contains(courier) || !askedThisPass.add(courier)) {
                        continue;
                    }
                    if (Workflow.currentTimeMillis() >= deadline) {
                        return hold(input);
                    }
                    DispatchResult result = offerTo(input, courier, target.weightGrams(), rejected);
                    if (result != null) {
                        return result;
                    }
                }
            }
            long remaining = deadline - Workflow.currentTimeMillis();
            if (remaining <= 0) {
                return hold(input);
            }
            Workflow.sleep(Duration.ofMillis(Math.min(RESCAN.toMillis(), remaining)));
        }
    }

    /** Sonuç kesinleştiyse (atandı ya da vazgeçildi) döner; sıradaki kuryeye geçilecekse null. */
    private DispatchResult offerTo(DispatchInput input, UUID courier, long weightGrams, Set<UUID> rejected) {
        UUID offerId = UUID.nameUUIDFromBytes((Workflow.getInfo().getWorkflowId() + ":offer:" + (++offerSequence))
                .getBytes(StandardCharsets.UTF_8));
        OfferInfo offer = activities.createOffer(offerId, input.shipmentId(), courier, weightGrams,
                OFFER_TTL_SECONDS);
        if (OfferInfo.REFUSED.equals(offer.status())) {
            return null;
        }

        long pollDeadline = Workflow.currentTimeMillis() + Duration.ofSeconds(OFFER_TTL_SECONDS).toMillis();
        Duration delay = POLL_INITIAL;
        while (OfferInfo.OFFERED.equals(offer.status())) {
            long remaining = pollDeadline - Workflow.currentTimeMillis();
            if (remaining <= 0) {
                offer = activities.expireOffer(offerId);
                if (OfferInfo.OFFERED.equals(offer.status())) {
                    Workflow.sleep(Duration.ofSeconds(1));
                }
                continue;
            }
            Workflow.sleep(Duration.ofMillis(Math.min(delay.toMillis(), remaining)));
            offer = activities.getOffer(offerId);
            delay = delay.multipliedBy(2).compareTo(POLL_MAX) > 0 ? POLL_MAX : delay.multipliedBy(2);
        }

        if (OfferInfo.ACCEPTED.equals(offer.status())) {
            return assign(input, courier);
        }
        if (OfferInfo.REJECTED.equals(offer.status())) {
            rejected.add(courier);
        }
        return null;
    }

    private DispatchResult assign(DispatchInput input, UUID courier) {
        try {
            critical.assignShipment(input.shipmentId(), courier);
            return new DispatchResult(DispatchResult.ASSIGNED, courier, null);
        } catch (ActivityFailure e) {
            critical.releaseCourier(courier, input.shipmentId());
            return new DispatchResult(DispatchResult.ABORTED, null, describe(e));
        }
    }

    private DispatchResult hold(DispatchInput input) {
        try {
            critical.holdShipment(input.shipmentId(), HOLD_REASON);
            return new DispatchResult(DispatchResult.ON_HOLD, null, HOLD_REASON);
        } catch (ActivityFailure e) {
            return new DispatchResult(DispatchResult.ABORTED, null, describe(e));
        }
    }

    private static String describe(ActivityFailure e) {
        Throwable cause = e.getCause();
        if (cause instanceof ApplicationFailure failure) {
            return failure.getOriginalMessage();
        }
        return cause == null ? e.getMessage() : cause.getMessage();
    }
}
