package com.routemesh.workflowservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.routemesh.contracts.fleet.FleetServiceGrpc;
import com.routemesh.contracts.fleet.ReleaseCourierByShipmentRequest;
import com.routemesh.contracts.fleet.ReleaseCourierResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class CourierReleaseRelayTest {

    private final FleetServiceGrpc.FleetServiceBlockingStub fleet = mock(FleetServiceGrpc.FleetServiceBlockingStub.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CourierReleaseRelay relay = new CourierReleaseRelay(fleet, JsonMapper.builder().build(), meters);
    private final UUID shipmentId = UUID.randomUUID();

    private void fleetAnswers(boolean released) {
        when(fleet.releaseCourierByShipment(any())).thenReturn(ReleaseCourierResponse.newBuilder()
                .setReleased(released).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERED", "CANCELLED", "LOST"})
    void aTerminalStatusReleasesTheCourierHoldingTheShipment(String to) {
        fleetAnswers(true);

        relay.onMessage(event("ShipmentStatusChanged", "OUT_FOR_DELIVERY", to));

        ArgumentCaptor<ReleaseCourierByShipmentRequest> request = ArgumentCaptor
                .forClass(ReleaseCourierByShipmentRequest.class);
        verify(fleet).releaseCourierByShipment(request.capture());
        assertThat(request.getValue().getShipmentId()).isEqualTo(shipmentId.toString());
        assertThat(releases("released")).isEqualTo(1);
    }

    @Test
    void cancellingAnAssignedShipmentStillReleasesEvenThoughTheEventCarriesNoCourier() {
        fleetAnswers(true);

        // ShipmentService ASSIGNED -> CANCELLED'da gönderideki kuryeyi temizler; olayda courierId yoktur
        relay.onMessage("{\"eventType\":\"ShipmentStatusChanged\",\"data\":{\"shipmentId\":\"" + shipmentId
                + "\",\"fromStatus\":\"ASSIGNED\",\"toStatus\":\"CANCELLED\",\"courierId\":null,\"version\":4}}");

        verify(fleet).releaseCourierByShipment(any());
    }

    @Test
    void aShipmentNobodyHoldsIsHarmlessAndCountedSeparately() {
        fleetAnswers(false);

        relay.onMessage(event("ShipmentStatusChanged", "PAID", "CANCELLED"));

        assertThat(releases("not_held")).isEqualTo(1);
        assertThat(releases("released")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "AT_WAREHOUSE", "READY_FOR_DISPATCH", "ASSIGNED", "OUT_FOR_DELIVERY",
            "DELIVERY_FAILED", "RETURN_TO_WAREHOUSE", "ON_HOLD", "DAMAGED"})
    void nonTerminalStatusesLeaveTheCourierAlone(String to) {
        relay.onMessage(event("ShipmentStatusChanged", "ASSIGNED", to));

        verifyNoInteractions(fleet);
    }

    @Test
    void otherEventTypesAreIgnored() {
        relay.onMessage(event("ShipmentCreated", null, "DELIVERED"));

        verify(fleet, never()).releaseCourierByShipment(any());
    }

    @Test
    void aFleetOutageIsPropagatedSoKafkaRedelivers() {
        when(fleet.releaseCourierByShipment(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThatThrownBy(() -> relay.onMessage(event("ShipmentStatusChanged", "OUT_FOR_DELIVERY", "DELIVERED")))
                .isInstanceOf(StatusRuntimeException.class);
        assertThat(releases("released")).isZero();
    }

    @Test
    void malformedMessagesFailFastForTheErrorHandlerToSkip() {
        assertThatThrownBy(() -> relay.onMessage("not json")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> relay.onMessage("{\"eventType\":\"ShipmentStatusChanged\",\"data\":"
                + "{\"shipmentId\":\"nope\",\"toStatus\":\"DELIVERED\"}}")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(fleet);
    }

    private double releases(String outcome) {
        var counter = meters.find("routemesh.workflow.courier.releases").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private String event(String type, String from, String to) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"data\":{\"shipmentId\":\""
                + shipmentId + "\",\"fromStatus\":" + (from == null ? "null" : "\"" + from + "\"")
                + ",\"toStatus\":\"" + to + "\",\"version\":3}}";
    }
}
