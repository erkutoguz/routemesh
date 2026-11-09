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

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
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
    private LocationServiceGrpc.LocationServiceStub asyncStub;
    private LocationServiceGrpc.LocationServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        channel = channels.createChannel("test");
        asyncStub = LocationServiceGrpc.newStub(channel);
        blockingStub = LocationServiceGrpc.newBlockingStub(channel);
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
        List<CourierLocationTelemetry> packets = List.of(
                telemetry(COURIER_A, 1, 1, LAT, LON),
                telemetry("not-a-uuid", 1, 2, LAT, LON));

        assertThatThrownBy(() -> stream(packets).get(10, TimeUnit.SECONDS))
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
        stream(List.of(
                telemetry(COURIER_A, 1, 1, LAT + 0.01, LON),     // ~1112 m
                telemetry(COURIER_B, 1, 1, LAT + 0.001, LON)))   // ~111 m
                .get(10, TimeUnit.SECONDS);

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

    // --- yardımcılar ---

    private CompletableFuture<StreamCourierLocationsResponse> stream(List<CourierLocationTelemetry> packets) {
        CompletableFuture<StreamCourierLocationsResponse> result = new CompletableFuture<>();
        StreamObserver<CourierLocationTelemetry> request = asyncStub.streamCourierLocations(
                new StreamObserver<>() {
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
        packets.forEach(request::onNext);
        request.onCompleted();
        return result;
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
