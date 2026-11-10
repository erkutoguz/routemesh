package com.routemesh.locationservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.grpc.client.GrpcChannelFactory;

import com.routemesh.contracts.common.LatLong;
import com.routemesh.contracts.location.CourierLocationTelemetry;
import com.routemesh.contracts.location.FindNearestRequest;
import com.routemesh.contracts.location.FindNearestResponse;
import com.routemesh.contracts.location.LocationServiceGrpc;
import com.routemesh.contracts.location.StreamCourierLocationsResponse;
import com.routemesh.locationservice.TestcontainersConfiguration;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@SpringBootTest(properties = {"routemesh.tracking.consumer-enabled=false", "routemesh.tracking.telemetry-enabled=false"})
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
@AutoConfigureTestGrpcTransport
class LocationGrpcServiceTest {

    private static final String COURIER_A = "11111111-1111-1111-1111-111111111111";
    private static final String COURIER_B = "22222222-2222-2222-2222-222222222222";
    private static final double LAT = 41.0;
    private static final double LON = 29.0;

    @Autowired
    private GrpcChannelFactory channels;

    @Autowired
    private StringRedisTemplate redis;

    private ManagedChannel channel;
    private LocationServiceGrpc.LocationServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        channel = channels.createChannel("test");
        blockingStub = LocationServiceGrpc.newBlockingStub(channel)
                .withInterceptors(TestJwts.bearer(TestJwts.token("workflow-sa", "WORKFLOW")));
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
    }

    // --- StreamCourierLocations ---

    @Test
    void stream_countsAcceptedAndStalePackets() throws Exception {
        List<CourierLocationTelemetry> packets = List.of(
                telemetry(COURIER_A, 1, 1, LAT, LON),
                telemetry(COURIER_A, 1, 2, LAT, LON),
                telemetry(COURIER_A, 1, 2, LAT, LON),   // aynı seq: eski sayılır
                telemetry(COURIER_A, 1, 1, LAT, LON));  // daha küçük seq: eski sayılır

        StreamCourierLocationsResponse response = stream(packets).get(10, TimeUnit.SECONDS);

        assertThat(response.getAccepted()).isEqualTo(2);
        assertThat(response.getRejectedStale()).isEqualTo(2);
    }

    @Test
    void stream_invalidPacket_closesStreamWithInvalidArgument() {
        List<CourierLocationTelemetry> packets = List.of(telemetry("not-a-uuid", 1, 2, LAT, LON));

        assertThatThrownBy(() -> stream("not-a-uuid", packets).get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(Status.fromThrowable(e).getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void stream_missingLocation_closesStreamWithInvalidArgument() {
        CourierLocationTelemetry noLocation = CourierLocationTelemetry.newBuilder()
                .setCourierId(COURIER_A).setSessionEpoch(1).setSequenceNumber(1).build();

        assertThatThrownBy(() -> stream(List.of(noLocation)).get(10, TimeUnit.SECONDS))
                .satisfies(e -> assertThat(Status.fromThrowable(e).getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    // --- FindNearestCouriers ---

    @Test
    void findNearest_returnsIngestedCouriersSortedByDistance() throws Exception {
        stream(List.of(telemetry(COURIER_A, 1, 1, LAT + 0.01, LON))).get(10, TimeUnit.SECONDS);     // ~1112 m
        stream(List.of(telemetry(COURIER_B, 1, 1, LAT + 0.001, LON))).get(10, TimeUnit.SECONDS);    // ~111 m

        FindNearestResponse response = blockingStub.findNearestCouriers(
                nearestRequest(LAT, LON, 2000, 10));

        assertThat(response.getCouriersList()).extracting(c -> c.getCourierId())
                .containsExactly(COURIER_B, COURIER_A);
        assertThat(response.getCouriers(0).getDistanceMeters()).isCloseTo(111.2, within(2.0));
    }

    @Test
    void findNearest_zeroLimit_usesDefault() throws Exception {
        stream(List.of(telemetry(COURIER_A, 1, 1, LAT + 0.001, LON))).get(10, TimeUnit.SECONDS);

        FindNearestResponse response = blockingStub.findNearestCouriers(
                nearestRequest(LAT, LON, 2000, 0));

        assertThat(response.getCouriersCount()).isEqualTo(1);
    }

    @Test
    void findNearest_radiusAboveMax_isInvalidArgument() {
        assertThatThrownBy(() -> blockingStub.findNearestCouriers(nearestRequest(LAT, LON, 50000, 10)))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void findNearest_missingTargetLocation_isInvalidArgument() {
        FindNearestRequest request = FindNearestRequest.newBuilder().setRadiusMeters(1000).build();

        assertThatThrownBy(() -> blockingStub.findNearestCouriers(request))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    // --- kimlik doğrulama ---

    @Test
    void stream_courierIdDifferentFromTokenSubject_isPermissionDenied() {
        assertCode(stream(COURIER_B, List.of(telemetry(COURIER_A, 1, 1, LAT, LON))), Status.Code.PERMISSION_DENIED);
    }

    @Test
    void stream_changingCourierIdMidStream_isPermissionDeniedAndNothingStoredForOther() {
        CompletableFuture<StreamCourierLocationsResponse> future = stream(COURIER_A, List.of(
                telemetry(COURIER_A, 1, 1, LAT, LON),
                telemetry(COURIER_B, 1, 1, LAT, LON)));

        assertCode(future, Status.Code.PERMISSION_DENIED);
        assertThat(blockingStub.findNearestCouriers(nearestRequest(LAT, LON, 2000, 10)).getCouriersList())
                .extracting(c -> c.getCourierId()).containsExactly(COURIER_A);
    }

    @Test
    void stream_withoutToken_isUnauthenticated() {
        CompletableFuture<StreamCourierLocationsResponse> result = new CompletableFuture<>();
        StreamObserver<CourierLocationTelemetry> request = open(null, result);
        request.onNext(telemetry(COURIER_A, 1, 1, LAT, LON));

        assertCode(result, Status.Code.UNAUTHENTICATED);
    }

    @Test
    void stream_withWorkflowToken_isPermissionDenied() {
        CompletableFuture<StreamCourierLocationsResponse> result = new CompletableFuture<>();
        StreamObserver<CourierLocationTelemetry> request =
                open(TestJwts.token("workflow-sa", "WORKFLOW"), result);
        request.onNext(telemetry(COURIER_A, 1, 1, LAT, LON));

        assertCode(result, Status.Code.PERMISSION_DENIED);
    }

    @Test
    void stream_tokenExpiringMidStream_isUnauthenticated() throws Exception {
        CompletableFuture<StreamCourierLocationsResponse> result = new CompletableFuture<>();
        StreamObserver<CourierLocationTelemetry> request = open(TestJwts.token(COURIER_A, 2, "COURIER"), result);
        request.onNext(telemetry(COURIER_A, 1, 1, LAT, LON));
        Thread.sleep(2500);
        request.onNext(telemetry(COURIER_A, 1, 2, LAT, LON));

        assertCode(result, Status.Code.UNAUTHENTICATED);
    }

    @Test
    void findNearest_requiresWorkflowRole() {
        LocationServiceGrpc.LocationServiceBlockingStub anonymous = LocationServiceGrpc.newBlockingStub(channel);
        LocationServiceGrpc.LocationServiceBlockingStub courier = LocationServiceGrpc.newBlockingStub(channel)
                .withInterceptors(TestJwts.bearer(TestJwts.token(COURIER_A, "COURIER")));
        FindNearestRequest request = nearestRequest(LAT, LON, 1000, 10);

        assertThatThrownBy(() -> anonymous.findNearestCouriers(request)).isInstanceOfSatisfying(
                StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThatThrownBy(() -> courier.findNearestCouriers(request)).isInstanceOfSatisfying(
                StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    // --- yardımcılar ---

    private CompletableFuture<StreamCourierLocationsResponse> stream(List<CourierLocationTelemetry> packets) {
        return stream(packets.get(0).getCourierId(), packets);
    }

    private CompletableFuture<StreamCourierLocationsResponse> stream(String subject,
                                                                    List<CourierLocationTelemetry> packets) {
        CompletableFuture<StreamCourierLocationsResponse> result = new CompletableFuture<>();
        StreamObserver<CourierLocationTelemetry> request = open(TestJwts.token(subject, "COURIER"), result);
        packets.forEach(request::onNext);
        request.onCompleted();
        return result;
    }

    private StreamObserver<CourierLocationTelemetry> open(String token,
                                                         CompletableFuture<StreamCourierLocationsResponse> result) {
        LocationServiceGrpc.LocationServiceStub stub = LocationServiceGrpc.newStub(channel);
        if (token != null) {
            stub = stub.withInterceptors(TestJwts.bearer(token));
        }
        return stub.streamCourierLocations(new StreamObserver<>() {
            @Override
            public void onNext(StreamCourierLocationsResponse value) {
                result.complete(value);
            }

            @Override
            public void onError(Throwable t) {
                result.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
            }
        });
    }

    private static void assertCode(CompletableFuture<?> future, Status.Code code) {
        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                .satisfies(e -> assertThat(Status.fromThrowable(e).getCode()).isEqualTo(code));
    }

    private static CourierLocationTelemetry telemetry(String courierId, long epoch, long seq,
                                                      double lat, double lon) {
        return CourierLocationTelemetry.newBuilder()
                .setCourierId(courierId)
                .setSessionEpoch(epoch)
                .setSequenceNumber(seq)
                .setLocation(LatLong.newBuilder().setLatitude(lat).setLongitude(lon))
                .build();
    }

    private static FindNearestRequest nearestRequest(double lat, double lon, double radius, int limit) {
        return FindNearestRequest.newBuilder()
                .setTargetLocation(LatLong.newBuilder().setLatitude(lat).setLongitude(lon))
                .setRadiusMeters(radius)
                .setLimit(limit)
                .build();
    }
}
