package com.routemesh.shipmentservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;

import com.routemesh.contracts.common.LatLong;
import com.routemesh.contracts.common.Money;
import com.routemesh.contracts.shipment.Actor;
import com.routemesh.contracts.shipment.CreateShipmentRequest;
import com.routemesh.contracts.shipment.CreateShipmentResponse;
import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.contracts.shipment.ShipmentStatus;
import com.routemesh.contracts.shipment.TransitionStatusRequest;
import com.routemesh.contracts.shipment.TransitionStatusResponse;
import com.routemesh.shipmentservice.TestcontainersConfiguration;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestGrpcTransport
class ShipmentGrpcServiceTest {

    @Autowired
    private GrpcChannelFactory channels;

    private ManagedChannel channel;
    private ShipmentServiceGrpc.ShipmentServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        channel = channels.createChannel("test");
        stub = ShipmentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
    }

    @Test
    void createAndGetShipment() {
        CreateShipmentRequest request = createRequest(key());

        CreateShipmentResponse response = stub.createShipment(request);
        Shipment fetched = stub.getShipment(GetShipmentRequest.newBuilder()
                .setShipmentId(response.getShipment().getId()).build());

        assertThat(response.getReplayed()).isFalse();
        assertThat(fetched.getStatus()).isEqualTo(ShipmentStatus.SHIPMENT_STATUS_PENDING_PAYMENT);
        assertThat(fetched.getMerchantId()).isEqualTo(request.getMerchantId());
        assertThat(fetched.hasCourierId()).isFalse();
        assertThat(fetched.getDeliveryLocation().getLatitude()).isEqualTo(40.9876);
        assertThat(fetched.getFinalAmount().getAmountMinor()).isEqualTo(1001);
        assertThat(fetched.getCreatedAt().getSeconds()).isPositive();
    }

    @Test
    void createShipment_sameRequestTwice_isReplayed() {
        CreateShipmentRequest request = createRequest(key());

        CreateShipmentResponse first = stub.createShipment(request);
        CreateShipmentResponse second = stub.createShipment(request);

        assertThat(second.getReplayed()).isTrue();
        assertThat(second.getShipment().getId()).isEqualTo(first.getShipment().getId());
    }

    @Test
    void createShipment_sameKeyDifferentPayload_isAlreadyExists() {
        String key = key();
        stub.createShipment(createRequest(key));

        CreateShipmentRequest different = createRequest(key).toBuilder().setRecipientName("Someone Else").build();

        assertCode(() -> stub.createShipment(different), Status.Code.ALREADY_EXISTS);
    }

    @Test
    void createShipment_invalidRequests_areInvalidArgument() {
        CreateShipmentRequest valid = createRequest(key());

        assertCode(() -> stub.createShipment(valid.toBuilder().setMerchantId("x").build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createShipment(valid.toBuilder().clearDeliveryLocation().build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createShipment(valid.toBuilder().clearFinalAmount().build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createShipment(valid.toBuilder()
                .setBasePrice(Money.newBuilder().setAmountMinor(800).setCurrency("USD")).build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createShipment(valid.toBuilder().setRecipientPhone("12").build()),
                Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void transitionStatus_movesShipmentAndAssignsCourier() {
        String id = stub.createShipment(createRequest(key())).getShipment().getId();
        String courier = UUID.randomUUID().toString();

        transition(id, ShipmentStatus.SHIPMENT_STATUS_PAID, Actor.ACTOR_SYSTEM);
        transition(id, ShipmentStatus.SHIPMENT_STATUS_AT_WAREHOUSE, Actor.ACTOR_WAREHOUSE);
        transition(id, ShipmentStatus.SHIPMENT_STATUS_READY_FOR_DISPATCH, Actor.ACTOR_WAREHOUSE);
        TransitionStatusResponse assigned = stub.transitionStatus(TransitionStatusRequest.newBuilder()
                .setShipmentId(id)
                .setToStatus(ShipmentStatus.SHIPMENT_STATUS_ASSIGNED)
                .setActor(Actor.ACTOR_SYSTEM)
                .setTransitionKey(key())
                .setCourierId(courier)
                .setExpectedFromStatus(ShipmentStatus.SHIPMENT_STATUS_READY_FOR_DISPATCH)
                .build());

        assertThat(assigned.getShipment().getStatus()).isEqualTo(ShipmentStatus.SHIPMENT_STATUS_ASSIGNED);
        assertThat(assigned.getShipment().getCourierId()).isEqualTo(courier);
        assertThat(assigned.getShipment().getVersion()).isEqualTo(4);
    }

    @Test
    void transitionStatus_sameKeyTwice_isReplayed() {
        String id = stub.createShipment(createRequest(key())).getShipment().getId();
        TransitionStatusRequest request = transitionRequest(id, ShipmentStatus.SHIPMENT_STATUS_PAID,
                Actor.ACTOR_SYSTEM);

        TransitionStatusResponse first = stub.transitionStatus(request);
        TransitionStatusResponse second = stub.transitionStatus(request);

        assertThat(first.getReplayed()).isFalse();
        assertThat(second.getReplayed()).isTrue();
    }

    @Test
    void transitionStatus_errors_areMappedToStatusCodes() {
        String id = stub.createShipment(createRequest(key())).getShipment().getId();

        assertCode(() -> stub.transitionStatus(transitionRequest(id, ShipmentStatus.SHIPMENT_STATUS_DELIVERED,
                Actor.ACTOR_COURIER)), Status.Code.FAILED_PRECONDITION);
        assertCode(() -> stub.transitionStatus(transitionRequest(id, ShipmentStatus.SHIPMENT_STATUS_PAID,
                Actor.ACTOR_MERCHANT)), Status.Code.FAILED_PRECONDITION);
        assertCode(() -> stub.transitionStatus(transitionRequest(UUID.randomUUID().toString(),
                ShipmentStatus.SHIPMENT_STATUS_PAID, Actor.ACTOR_SYSTEM)), Status.Code.NOT_FOUND);
        assertCode(() -> stub.transitionStatus(transitionRequest(id, ShipmentStatus.SHIPMENT_STATUS_UNSPECIFIED,
                Actor.ACTOR_SYSTEM)), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.transitionStatus(transitionRequest(id, ShipmentStatus.SHIPMENT_STATUS_PAID,
                Actor.ACTOR_UNSPECIFIED)), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.transitionStatus(transitionRequest("not-a-uuid", ShipmentStatus.SHIPMENT_STATUS_PAID,
                Actor.ACTOR_SYSTEM)), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void transitionStatus_sameKeyDifferentPayload_isAlreadyExists() {
        String id = stub.createShipment(createRequest(key())).getShipment().getId();
        String key = key();
        stub.transitionStatus(transitionRequest(id, ShipmentStatus.SHIPMENT_STATUS_PAID, Actor.ACTOR_SYSTEM)
                .toBuilder().setTransitionKey(key).build());

        assertCode(() -> stub.transitionStatus(transitionRequest(id, ShipmentStatus.SHIPMENT_STATUS_CANCELLED,
                Actor.ACTOR_SYSTEM).toBuilder().setTransitionKey(key).build()), Status.Code.ALREADY_EXISTS);
    }

    @Test
    void getShipment_unknownOrInvalid() {
        assertCode(() -> stub.getShipment(GetShipmentRequest.newBuilder()
                .setShipmentId(UUID.randomUUID().toString()).build()), Status.Code.NOT_FOUND);
        assertCode(() -> stub.getShipment(GetShipmentRequest.newBuilder().setShipmentId("x").build()),
                Status.Code.INVALID_ARGUMENT);
    }

    private void transition(String id, ShipmentStatus to, Actor actor) {
        stub.transitionStatus(transitionRequest(id, to, actor));
    }

    private static TransitionStatusRequest transitionRequest(String id, ShipmentStatus to, Actor actor) {
        return TransitionStatusRequest.newBuilder()
                .setShipmentId(id)
                .setToStatus(to)
                .setActor(actor)
                .setTransitionKey(key())
                .build();
    }

    private static CreateShipmentRequest createRequest(String key) {
        return CreateShipmentRequest.newBuilder()
                .setIdempotencyKey(key)
                .setMerchantId(UUID.randomUUID().toString())
                .setRecipientName("Ada Lovelace")
                .setRecipientPhone("+905551112233")
                .setDeliveryAddress("Moda Cd. 12, Kadikoy")
                .setDeliveryLocation(LatLong.newBuilder().setLatitude(40.9876).setLongitude(29.0369))
                .setQuoteId(UUID.randomUUID().toString())
                .setFinalAmount(Money.newBuilder().setAmountMinor(1001).setCurrency("TRY"))
                .setBasePrice(Money.newBuilder().setAmountMinor(800).setCurrency("TRY"))
                .build();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static void assertCode(Runnable call, Status.Code code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(code));
    }
}
