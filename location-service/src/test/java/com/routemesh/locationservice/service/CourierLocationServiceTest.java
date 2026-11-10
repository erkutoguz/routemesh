package com.routemesh.locationservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.routemesh.locationservice.config.LocationProperties;
import com.routemesh.locationservice.kafka.ApproachPublisher;
import com.routemesh.locationservice.kafka.TelemetryPublisher;
import com.routemesh.locationservice.store.CourierLocationStore;
import com.routemesh.locationservice.store.IngestResult;
import com.routemesh.locationservice.store.NearbyCourier;
import com.routemesh.locationservice.store.ProximityHit;

class CourierLocationServiceTest {

    private static final String VALID_ID = "123e4567-e89b-12d3-a456-426614174000";

    private final CourierLocationStore store = mock(CourierLocationStore.class);
    private final ApproachPublisher approach = mock(ApproachPublisher.class);
    private final TelemetryPublisher telemetry = mock(TelemetryPublisher.class);
    private final CourierLocationService service = new CourierLocationService(
            store, new LocationProperties(Duration.ofSeconds(60), Duration.ofHours(24), 20, 100, 15000),
            approach, telemetry);

    // --- ingest ---

    @Test
    void ingest_validPacket_isForwardedToStoreAndTelemetry() {
        when(store.ingest(VALID_ID, 1, 5, 41.0, 29.0)).thenReturn(IngestResult.ACCEPTED);

        assertThat(service.ingest(VALID_ID, 1, 5, 41.0, 29.0)).isTrue();
        verify(telemetry).offer(VALID_ID, 1, 5, 41.0, 29.0);
        verifyNoInteractions(approach);
    }

    @Test
    void ingest_storeRejectsStalePacket_returnsFalseAndPublishesNothing() {
        when(store.ingest(VALID_ID, 1, 5, 41.0, 29.0)).thenReturn(IngestResult.STALE);

        assertThat(service.ingest(VALID_ID, 1, 5, 41.0, 29.0)).isFalse();
        verifyNoInteractions(telemetry, approach);
    }

    @Test
    void ingest_packetThatTriggersProximity_publishesTheApproachEvent() {
        ProximityHit hit = new ProximityHit(VALID_ID, "shipment-1", "DELIVERY", 82.4);
        when(store.ingest(VALID_ID, 1, 5, 41.0, 29.0)).thenReturn(new IngestResult(true, hit));

        assertThat(service.ingest(VALID_ID, 1, 5, 41.0, 29.0)).isTrue();
        verify(approach).publish(hit, 41.0, 29.0);
    }

    @Test
    void ingest_upperCaseUuid_isNormalizedToLowerCase() {
        when(store.ingest(VALID_ID, 1, 5, 41.0, 29.0)).thenReturn(IngestResult.ACCEPTED);

        service.ingest(VALID_ID.toUpperCase(), 1, 5, 41.0, 29.0);

        verify(store).ingest(VALID_ID, 1, 5, 41.0, 29.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid", "1-1-1-1-1", "123e4567e89b12d3a456426614174000",
            "123e4567-e89b-12d3-a456-42661417400z"})
    void ingest_invalidCourierId_isRejected(String courierId) {
        assertThatThrownBy(() -> service.ingest(courierId, 1, 5, 41.0, 29.0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("courier_id");
        verifyNoInteractions(store);
    }

    @Test
    void ingest_nullCourierId_isRejected() {
        assertThatThrownBy(() -> service.ingest(null, 1, 5, 41.0, 29.0))
                .isInstanceOf(InvalidRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {85.06, -85.06, 90.0, Double.NaN, Double.POSITIVE_INFINITY})
    void ingest_invalidLatitude_isRejected(double latitude) {
        assertThatThrownBy(() -> service.ingest(VALID_ID, 1, 5, latitude, 29.0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("latitude");
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(doubles = {180.1, -180.1, Double.NaN, Double.NEGATIVE_INFINITY})
    void ingest_invalidLongitude_isRejected(double longitude) {
        assertThatThrownBy(() -> service.ingest(VALID_ID, 1, 5, 41.0, longitude))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("longitude");
        verifyNoInteractions(store);
    }

    @Test
    void ingest_negativeEpochOrSequence_isRejected() {
        assertThatThrownBy(() -> service.ingest(VALID_ID, -1, 5, 41.0, 29.0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("session_epoch");
        assertThatThrownBy(() -> service.ingest(VALID_ID, 1, -1, 41.0, 29.0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("sequence_number");
        verifyNoInteractions(store);
    }

    // --- findNearest ---

    @Test
    void findNearest_zeroLimit_usesDefaultLimit() {
        service.findNearest(41.0, 29.0, 1000, 0);

        verify(store).findNearest(41.0, 29.0, 1000, 20);
    }

    @Test
    void findNearest_limitAboveMax_isCappedToMax() {
        service.findNearest(41.0, 29.0, 1000, 500);

        verify(store).findNearest(41.0, 29.0, 1000, 100);
    }

    @Test
    void findNearest_limitWithinRange_isPassedAsIs() {
        service.findNearest(41.0, 29.0, 1000, 7);

        verify(store).findNearest(41.0, 29.0, 1000, 7);
    }

    @Test
    void findNearest_returnsStoreResult() {
        List<NearbyCourier> expected = List.of(new NearbyCourier(VALID_ID, 111.2));
        when(store.findNearest(anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(expected);

        assertThat(service.findNearest(41.0, 29.0, 1000, 10)).isEqualTo(expected);
    }

    @Test
    void findNearest_negativeLimit_isRejected() {
        assertThatThrownBy(() -> service.findNearest(41.0, 29.0, 1000, -1))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("limit");
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -5, 15000.1, 100000, Double.NaN, Double.POSITIVE_INFINITY})
    void findNearest_invalidRadius_isRejected(double radius) {
        assertThatThrownBy(() -> service.findNearest(41.0, 29.0, radius, 10))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("radius_meters");
        verifyNoInteractions(store);
    }

    @Test
    void findNearest_maxRadius_isAccepted() {
        service.findNearest(41.0, 29.0, 15000, 10);

        verify(store).findNearest(41.0, 29.0, 15000, 10);
    }

    @Test
    void findNearest_invalidTargetCoordinates_areRejected() {
        assertThatThrownBy(() -> service.findNearest(Double.NaN, 29.0, 1000, 10))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.findNearest(41.0, 200.0, 1000, 10))
                .isInstanceOf(InvalidRequestException.class);
        verify(store, org.mockito.Mockito.never())
                .ingest(anyString(), anyLong(), anyLong(), anyDouble(), anyDouble());
    }
}
