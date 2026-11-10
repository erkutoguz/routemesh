package com.routemesh.workflowservice.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.routemesh.contracts.fleet.CreateOfferRequest;
import com.routemesh.contracts.fleet.CreateOfferResponse;
import com.routemesh.contracts.fleet.ExpireOfferRequest;
import com.routemesh.contracts.fleet.FleetServiceGrpc;
import com.routemesh.contracts.fleet.Offer;
import com.routemesh.contracts.fleet.OfferStatus;
import com.routemesh.contracts.location.CourierDistance;
import com.routemesh.contracts.location.FindNearestRequest;
import com.routemesh.contracts.location.FindNearestResponse;
import com.routemesh.contracts.location.LocationServiceGrpc;
import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.ShipmentStatus;
import com.routemesh.contracts.shipment.TransitionStatusRequest;
import com.routemesh.workflowservice.config.DispatchProperties;
import com.routemesh.workflowservice.workflow.DispatchTarget;
import com.routemesh.workflowservice.workflow.OfferInfo;

import io.grpc.Status;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;

class DispatchActivitiesImplTest {

    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments =
            mock(ShipmentServiceGrpc.ShipmentServiceBlockingStub.class);
    private final LocationServiceGrpc.LocationServiceBlockingStub locations =
            mock(LocationServiceGrpc.LocationServiceBlockingStub.class);
    private final FleetServiceGrpc.FleetServiceBlockingStub fleet =
            mock(FleetServiceGrpc.FleetServiceBlockingStub.class);

    private final UUID shipmentId = UUID.randomUUID();
    private final UUID courierId = UUID.randomUUID();
    private final UUID offerId = UUID.randomUUID();

    private TestActivityEnvironment env;
    private DispatchActivities activities;

    @BeforeEach
    void setUp() {
        env = TestActivityEnvironment.newInstance();
        env.registerActivitiesImplementations(new DispatchActivitiesImpl(shipments, locations, fleet,
                new DispatchProperties(41.0, 29.0, 1000, 50)));
        activities = env.newActivityStub(DispatchActivities.class, ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2)
                        .setInitialInterval(Duration.ofMillis(10)).build())
                .build());
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void loadShipment_returnsStatusAndWeightOrTheConfiguredDefault() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(
                Shipment.newBuilder().setStatus(ShipmentStatus.SHIPMENT_STATUS_READY_FOR_DISPATCH)
                        .setWeightGrams(2500).build(),
                Shipment.newBuilder().setStatus(ShipmentStatus.SHIPMENT_STATUS_ON_HOLD).build());

        assertThat(activities.loadShipment(shipmentId)).isEqualTo(new DispatchTarget("READY_FOR_DISPATCH", 2500));
        assertThat(activities.loadShipment(shipmentId)).isEqualTo(new DispatchTarget("ON_HOLD", 1000));
    }

    @Test
    void findCouriers_searchesAroundTheDepotAndSkipsNonUuidIds() {
        when(locations.findNearestCouriers(any(FindNearestRequest.class))).thenReturn(FindNearestResponse
                .newBuilder()
                .addCouriers(CourierDistance.newBuilder().setCourierId(courierId.toString()).setDistanceMeters(10))
                .addCouriers(CourierDistance.newBuilder().setCourierId("courier-7").setDistanceMeters(20))
                .build());

        List<UUID> found = activities.findCouriers(5_000);

        ArgumentCaptor<FindNearestRequest> request = ArgumentCaptor.forClass(FindNearestRequest.class);
        verify(locations).findNearestCouriers(request.capture());
        assertThat(found).containsExactly(courierId);
        assertThat(request.getValue().getRadiusMeters()).isEqualTo(5_000);
        assertThat(request.getValue().getLimit()).isEqualTo(50);
        assertThat(request.getValue().getTargetLocation().getLatitude()).isEqualTo(41.0);
        assertThat(request.getValue().getTargetLocation().getLongitude()).isEqualTo(29.0);
    }

    @Test
    void createOffer_mapsStatusAndTurnsIneligibleCourierIntoRefused() {
        when(fleet.createOffer(any(CreateOfferRequest.class)))
                .thenReturn(CreateOfferResponse.newBuilder()
                        .setOffer(Offer.newBuilder().setStatus(OfferStatus.OFFER_STATUS_OFFERED)).build())
                .thenThrow(Status.FAILED_PRECONDITION.withDescription("courier is BUSY").asRuntimeException());

        assertThat(activities.createOffer(offerId, shipmentId, courierId, 2500, 60))
                .isEqualTo(new OfferInfo("OFFERED"));
        assertThat(activities.createOffer(offerId, shipmentId, courierId, 2500, 60))
                .isEqualTo(new OfferInfo("REFUSED"));

        ArgumentCaptor<CreateOfferRequest> request = ArgumentCaptor.forClass(CreateOfferRequest.class);
        verify(fleet, org.mockito.Mockito.times(2)).createOffer(request.capture());
        assertThat(request.getAllValues().get(0).getOfferId()).isEqualTo(offerId.toString());
        assertThat(request.getAllValues().get(0).getWeightGrams()).isEqualTo(2500);
        assertThat(request.getAllValues().get(0).getTtlSeconds()).isEqualTo(60);
    }

    @Test
    void createOffer_otherBusinessErrorsFailWithoutRetry() {
        when(fleet.createOffer(any(CreateOfferRequest.class)))
                .thenThrow(Status.INVALID_ARGUMENT.withDescription("bad weight").asRuntimeException());

        assertThatThrownBy(() -> activities.createOffer(offerId, shipmentId, courierId, 0, 60))
                .isInstanceOf(ActivityFailure.class)
                .hasRootCauseInstanceOf(ApplicationFailure.class);
    }

    @Test
    void expireOffer_returnsTheOfferAsItIsNow() {
        when(fleet.expireOffer(any(ExpireOfferRequest.class))).thenReturn(
                Offer.newBuilder().setStatus(OfferStatus.OFFER_STATUS_ACCEPTED).build());

        assertThat(activities.expireOffer(offerId)).isEqualTo(new OfferInfo("ACCEPTED"));
    }

    @Test
    void assignShipment_transitionsFromReadyWithCourierAndStableKey() {
        activities.assignShipment(shipmentId, courierId);

        ArgumentCaptor<TransitionStatusRequest> request = ArgumentCaptor.forClass(TransitionStatusRequest.class);
        verify(shipments).transitionStatus(request.capture());
        TransitionStatusRequest sent = request.getValue();
        assertThat(sent.getToStatus()).isEqualTo(ShipmentStatus.SHIPMENT_STATUS_ASSIGNED);
        assertThat(sent.getExpectedFromStatus()).isEqualTo(ShipmentStatus.SHIPMENT_STATUS_READY_FOR_DISPATCH);
        assertThat(sent.getCourierId()).isEqualTo(courierId.toString());
        assertThat(sent.getTransitionKey()).endsWith(":assign");
    }

    @Test
    void holdShipment_putsReadyShipmentOnHoldWithReason() {
        activities.holdShipment(shipmentId, "NO_COURIER_ACCEPTED");

        ArgumentCaptor<TransitionStatusRequest> request = ArgumentCaptor.forClass(TransitionStatusRequest.class);
        verify(shipments).transitionStatus(request.capture());
        assertThat(request.getValue().getToStatus()).isEqualTo(ShipmentStatus.SHIPMENT_STATUS_ON_HOLD);
        assertThat(request.getValue().getReason()).isEqualTo("NO_COURIER_ACCEPTED");
        assertThat(request.getValue().getExpectedFromStatus())
                .isEqualTo(ShipmentStatus.SHIPMENT_STATUS_READY_FOR_DISPATCH);
    }

    @Test
    void assignShipment_rejectedByShipmentServiceIsNotRetried() {
        when(shipments.transitionStatus(any(TransitionStatusRequest.class)))
                .thenThrow(Status.FAILED_PRECONDITION.withDescription("shipment is CANCELLED").asRuntimeException());

        assertThatThrownBy(() -> activities.assignShipment(shipmentId, courierId))
                .isInstanceOf(ActivityFailure.class)
                .hasStackTraceContaining("FAILED_PRECONDITION: shipment is CANCELLED");
    }
}
