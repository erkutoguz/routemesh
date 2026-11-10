package com.routemesh.locationservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.routemesh.contracts.common.LatLong;
import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.ShipmentStatus;
import com.routemesh.locationservice.config.TrackingProperties;
import com.routemesh.locationservice.store.CourierLocationStore;
import com.routemesh.locationservice.store.Watch;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import tools.jackson.databind.json.JsonMapper;

class ShipmentWatchConsumerTest {

    private final CourierLocationStore store = mock(CourierLocationStore.class);
    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments =
            mock(ShipmentServiceGrpc.ShipmentServiceBlockingStub.class);
    private final TrackingProperties properties = new TrackingProperties(100, 41.0082, 28.9784,
            Duration.ofHours(48), Duration.ofHours(24), "location.events", "courier.locations", true, 10);
    private final ShipmentWatchConsumer consumer =
            new ShipmentWatchConsumer(store, shipments, properties, JsonMapper.builder().build());

    private final UUID shipmentId = UUID.randomUUID();
    private final UUID courierId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();

    private Shipment.Builder shipment(ShipmentStatus status) {
        return Shipment.newBuilder().setId(shipmentId.toString()).setMerchantId(merchantId.toString())
                .setStatus(status).setCourierId(courierId.toString())
                .setDeliveryLocation(LatLong.newBuilder().setLatitude(41.05).setLongitude(29.02));
    }

    private String event(String type, String to, long version, UUID courier) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"data\":{\"shipmentId\":\""
                + shipmentId + "\",\"toStatus\":\"" + to + "\",\"courierId\":"
                + (courier == null ? "null" : "\"" + courier + "\"") + ",\"version\":" + version + "}}";
    }

    private Watch armedWatch() {
        ArgumentCaptor<Watch> watch = ArgumentCaptor.forClass(Watch.class);
        verify(store).armWatch(watch.capture());
        return watch.getValue();
    }

    @Test
    void assigned_armsAPickupWatchAtTheDepot() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(shipment(ShipmentStatus.SHIPMENT_STATUS_ASSIGNED).build());

        consumer.onMessage(event("ShipmentStatusChanged", "ASSIGNED", 5, courierId));

        Watch watch = armedWatch();
        assertThat(watch.kind()).isEqualTo(Watch.PICKUP);
        assertThat(watch.latitude()).isEqualTo(41.0082);
        assertThat(watch.longitude()).isEqualTo(28.9784);
        assertThat(watch.shipmentId()).isEqualTo(shipmentId.toString());
        assertThat(watch.courierId()).isEqualTo(courierId.toString());
        assertThat(watch.merchantId()).isEqualTo(merchantId.toString());
        assertThat(watch.version()).isEqualTo(5);
    }

    @Test
    void outForDelivery_armsADeliveryWatchAtTheDeliveryLocation() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(shipment(ShipmentStatus.SHIPMENT_STATUS_OUT_FOR_DELIVERY).build());

        consumer.onMessage(event("ShipmentStatusChanged", "OUT_FOR_DELIVERY", 6, courierId));

        Watch watch = armedWatch();
        assertThat(watch.kind()).isEqualTo(Watch.DELIVERY);
        assertThat(watch.latitude()).isEqualTo(41.05);
        assertThat(watch.longitude()).isEqualTo(29.02);
        assertThat(watch.version()).isEqualTo(6);
    }

    @Test
    void courierMissingInTheEvent_isTakenFromTheShipment() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(shipment(ShipmentStatus.SHIPMENT_STATUS_OUT_FOR_DELIVERY).build());

        consumer.onMessage(event("ShipmentStatusChanged", "OUT_FOR_DELIVERY", 6, null));

        assertThat(armedWatch().courierId()).isEqualTo(courierId.toString());
    }

    @Test
    void noCourierAnywhere_isIgnored() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(
                shipment(ShipmentStatus.SHIPMENT_STATUS_OUT_FOR_DELIVERY).clearCourierId().build());

        consumer.onMessage(event("ShipmentStatusChanged", "OUT_FOR_DELIVERY", 6, null));

        verify(store, never()).armWatch(any());
    }

    @Test
    void shipmentAlreadyMovedOn_doesNotArmTheOlderStepsWatch() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(shipment(ShipmentStatus.SHIPMENT_STATUS_OUT_FOR_DELIVERY).build());

        consumer.onMessage(event("ShipmentStatusChanged", "ASSIGNED", 5, courierId));

        verify(store, never()).armWatch(any());
    }

    @Test
    void outForDeliveryWithoutDeliveryLocation_isIgnored() {
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(
                shipment(ShipmentStatus.SHIPMENT_STATUS_OUT_FOR_DELIVERY).clearDeliveryLocation().build());

        consumer.onMessage(event("ShipmentStatusChanged", "OUT_FOR_DELIVERY", 6, courierId));

        verify(store, never()).armWatch(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERED", "DELIVERY_FAILED", "CANCELLED", "LOST", "RETURN_TO_WAREHOUSE", "ON_HOLD"})
    void endStatuses_clearTheWatchWithoutCallingShipmentService(String status) {
        consumer.onMessage(event("ShipmentStatusChanged", status, 9, courierId));

        verify(store).clearWatch(shipmentId.toString(), status);
        verifyNoInteractions(shipments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "AT_WAREHOUSE", "READY_FOR_DISPATCH", "PENDING_PAYMENT"})
    void otherStatuses_areIgnored(String status) {
        consumer.onMessage(event("ShipmentStatusChanged", status, 3, courierId));

        verifyNoInteractions(store, shipments);
    }

    @Test
    void otherEventTypes_areIgnored() {
        consumer.onMessage(event("ShipmentCreated", "ASSIGNED", 1, courierId));

        verifyNoInteractions(store, shipments);
    }

    @Test
    void shipmentNotFound_isIgnored() {
        when(shipments.getShipment(any(GetShipmentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

        consumer.onMessage(event("ShipmentStatusChanged", "ASSIGNED", 5, courierId));

        verify(store, never()).armWatch(any());
    }

    @Test
    void shipmentServiceOutage_isPropagatedSoKafkaRedelivers() {
        when(shipments.getShipment(any(GetShipmentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThatThrownBy(() -> consumer.onMessage(event("ShipmentStatusChanged", "ASSIGNED", 5, courierId)))
                .isInstanceOf(StatusRuntimeException.class);
    }

    @Test
    void malformedMessage_failsFastForTheErrorHandlerToSkip() {
        assertThatThrownBy(() -> consumer.onMessage("not json")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(store, shipments);
    }
}
