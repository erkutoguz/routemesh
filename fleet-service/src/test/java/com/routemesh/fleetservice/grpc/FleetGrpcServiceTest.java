package com.routemesh.fleetservice.grpc;

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

import com.routemesh.contracts.fleet.CreateOfferRequest;
import com.routemesh.contracts.fleet.CreateOfferResponse;
import com.routemesh.contracts.fleet.ExpireOfferRequest;
import com.routemesh.contracts.fleet.FleetServiceGrpc;
import com.routemesh.contracts.fleet.GetOfferRequest;
import com.routemesh.contracts.fleet.Offer;
import com.routemesh.contracts.fleet.OfferStatus;
import com.routemesh.contracts.fleet.ReleaseCourierByShipmentRequest;
import com.routemesh.contracts.fleet.ReleaseCourierRequest;
import com.routemesh.fleetservice.TestcontainersConfiguration;
import com.routemesh.fleetservice.domain.CourierStatus;
import com.routemesh.fleetservice.service.FleetService;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
@AutoConfigureTestGrpcTransport
class FleetGrpcServiceTest {

    @Autowired
    private GrpcChannelFactory channels;

    @Autowired
    private FleetService fleetService;

    private ManagedChannel channel;
    private FleetServiceGrpc.FleetServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        channel = channels.createChannel("test");
        stub = FleetServiceGrpc.newBlockingStub(channel)
                .withInterceptors(TestJwts.bearer(TestJwts.token("workflow-sa", "WORKFLOW")));
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
    }

    @Test
    void offerLifecycleThroughGrpc() {
        UUID courier = idleCourier();
        CreateOfferRequest request = request(courier, UUID.randomUUID(), 1500);

        CreateOfferResponse created = stub.createOffer(request);
        CreateOfferResponse replay = stub.createOffer(request);
        Offer fetched = stub.getOffer(GetOfferRequest.newBuilder().setOfferId(request.getOfferId()).build());

        assertThat(created.getReplayed()).isFalse();
        assertThat(created.getOffer().getStatus()).isEqualTo(OfferStatus.OFFER_STATUS_OFFERED);
        assertThat(created.getOffer().getFenceToken()).isPositive();
        assertThat(created.getOffer().getExpiresAt().getSeconds()).isPositive();
        assertThat(created.getOffer().hasDecidedAt()).isFalse();
        assertThat(replay.getReplayed()).isTrue();
        assertThat(fetched.getWeightGrams()).isEqualTo(1500);

        fleetService.accept(courier, UUID.fromString(request.getOfferId()));

        Offer expired = stub.expireOffer(ExpireOfferRequest.newBuilder().setOfferId(request.getOfferId()).build());
        assertThat(expired.getStatus()).isEqualTo(OfferStatus.OFFER_STATUS_ACCEPTED);
        assertThat(expired.hasDecidedAt()).isTrue();

        var release = ReleaseCourierRequest.newBuilder()
                .setCourierId(courier.toString()).setShipmentId(request.getShipmentId()).build();
        assertThat(stub.releaseCourier(release).getReleased()).isTrue();
        assertThat(stub.releaseCourier(release).getReleased()).isFalse();
        assertThat(stub.getOffer(GetOfferRequest.newBuilder().setOfferId(request.getOfferId()).build()).getStatus())
                .isEqualTo(OfferStatus.OFFER_STATUS_RELEASED);
    }

    @Test
    void releaseByShipmentNeedsNoCourierIdAndIsIdempotent() {
        UUID courier = idleCourier();
        CreateOfferRequest request = request(courier, UUID.randomUUID(), 1000);
        stub.createOffer(request);
        fleetService.accept(courier, UUID.fromString(request.getOfferId()));
        var release = ReleaseCourierByShipmentRequest.newBuilder().setShipmentId(request.getShipmentId()).build();

        assertThat(stub.releaseCourierByShipment(release).getReleased()).isTrue();
        assertThat(stub.releaseCourierByShipment(release).getReleased()).isFalse();
        assertThat(fleetService.findCourier(courier).orElseThrow().status()).isEqualTo(CourierStatus.IDLE);
        assertCode(() -> stub.releaseCourierByShipment(ReleaseCourierByShipmentRequest.newBuilder()
                .setShipmentId("x").build()), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void errorsAreMappedToStatusCodes() {
        UUID courier = idleCourier();
        UUID shipment = UUID.randomUUID();
        CreateOfferRequest first = request(courier, shipment, 1000);
        stub.createOffer(first);

        assertCode(() -> stub.createOffer(request(courier, UUID.randomUUID(), 1000)),
                Status.Code.FAILED_PRECONDITION);
        assertCode(() -> stub.createOffer(request(UUID.randomUUID(), UUID.randomUUID(), 1000)),
                Status.Code.FAILED_PRECONDITION);
        assertCode(() -> stub.createOffer(first.toBuilder().setShipmentId(UUID.randomUUID().toString()).build()),
                Status.Code.ALREADY_EXISTS);
        assertCode(() -> stub.createOffer(request(courier, shipment, 0)), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createOffer(first.toBuilder().setCourierId("x").build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.getOffer(GetOfferRequest.newBuilder().setOfferId(UUID.randomUUID().toString()).build()),
                Status.Code.NOT_FOUND);
        assertCode(() -> stub.releaseCourier(ReleaseCourierRequest.newBuilder()
                        .setCourierId("x").setShipmentId(shipment.toString()).build()),
                Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void callsWithoutTokenAreUnauthenticated() {
        FleetServiceGrpc.FleetServiceBlockingStub anonymous = FleetServiceGrpc.newBlockingStub(channel);
        FleetServiceGrpc.FleetServiceBlockingStub forged = FleetServiceGrpc.newBlockingStub(channel)
                .withInterceptors(TestJwts.bearer("not.a.jwt"));
        FleetServiceGrpc.FleetServiceBlockingStub expired = FleetServiceGrpc.newBlockingStub(channel)
                .withInterceptors(TestJwts.bearer(TestJwts.token("workflow-sa", -60, "WORKFLOW")));
        CreateOfferRequest request = request(UUID.randomUUID(), UUID.randomUUID(), 1000);

        assertCode(() -> anonymous.createOffer(request), Status.Code.UNAUTHENTICATED);
        assertCode(() -> forged.createOffer(request), Status.Code.UNAUTHENTICATED);
        assertCode(() -> expired.createOffer(request), Status.Code.UNAUTHENTICATED);
    }

    @Test
    void callsWithoutWorkflowRoleArePermissionDenied() {
        for (String role : new String[] {"MERCHANT", "COURIER", "WAREHOUSE", "OPS"}) {
            FleetServiceGrpc.FleetServiceBlockingStub other = FleetServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(TestJwts.bearer(TestJwts.token(UUID.randomUUID().toString(), role)));

            assertCode(() -> other.createOffer(request(UUID.randomUUID(), UUID.randomUUID(), 1000)),
                    Status.Code.PERMISSION_DENIED);
            assertCode(() -> other.releaseCourierByShipment(ReleaseCourierByShipmentRequest.newBuilder()
                    .setShipmentId(UUID.randomUUID().toString()).build()), Status.Code.PERMISSION_DENIED);
        }
    }

    private UUID idleCourier() {
        UUID id = UUID.randomUUID();
        fleetService.setStatus(id, CourierStatus.IDLE);
        return id;
    }

    private static CreateOfferRequest request(UUID courier, UUID shipment, long weight) {
        return CreateOfferRequest.newBuilder()
                .setOfferId(UUID.randomUUID().toString())
                .setShipmentId(shipment.toString())
                .setCourierId(courier.toString())
                .setWeightGrams(weight)
                .setTtlSeconds(60)
                .build();
    }

    private static void assertCode(Runnable call, Status.Code code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(code));
    }
}
