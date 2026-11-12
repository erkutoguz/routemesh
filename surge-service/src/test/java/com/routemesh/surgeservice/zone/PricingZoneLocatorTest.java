package com.routemesh.surgeservice.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.routemesh.contracts.pricing.LocateZoneRequest;
import com.routemesh.contracts.pricing.LocateZoneResponse;
import com.routemesh.contracts.pricing.PricingServiceGrpc;
import com.routemesh.surgeservice.config.SurgeProperties;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

class PricingZoneLocatorTest {

    private final PricingServiceGrpc.PricingServiceBlockingStub pricing =
            mock(PricingServiceGrpc.PricingServiceBlockingStub.class);
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final PricingZoneLocator locator = new PricingZoneLocator(pricing,
            new SurgeProperties("a", "b", "c", Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofSeconds(30), 3,
                    25_000, 500, 1_000, Duration.ofMinutes(10), 0.005, Duration.ofHours(1), 6), now::get);

    @Test
    void asksPricingForTheZoneOfTheCellCenter() {
        when(pricing.locateZone(any(LocateZoneRequest.class)))
                .thenReturn(LocateZoneResponse.newBuilder().setZoneCode("IST_ASIA_CORE").build());

        assertThat(locator.locate(40.9876, 29.0369)).contains("IST_ASIA_CORE");

        ArgumentCaptor<LocateZoneRequest> request = ArgumentCaptor.forClass(LocateZoneRequest.class);
        verify(pricing).locateZone(request.capture());
        assertThat(request.getValue().getLocation().getLatitude()).isEqualTo(Math.round(40.9876 / 0.005) * 0.005);
        assertThat(request.getValue().getLocation().getLongitude()).isEqualTo(Math.round(29.0369 / 0.005) * 0.005);
    }

    @Test
    void pointsInTheSameCellShareOneLookup_otherCellsDoNot() {
        when(pricing.locateZone(any(LocateZoneRequest.class)))
                .thenReturn(LocateZoneResponse.newBuilder().setZoneCode("Z").build());

        locator.locate(41.0001, 29.0001);
        locator.locate(41.0010, 29.0020);   // aynı 0,005° hücresi
        locator.locate(41.0024, 29.0012);   // aynı hücre (sınır 41,0025)
        verify(pricing, times(1)).locateZone(any());

        locator.locate(41.0100, 29.0000);   // başka hücre
        verify(pricing, times(2)).locateZone(any());
    }

    @Test
    void outsideTheServiceAreaIsCachedToo() {
        when(pricing.locateZone(any(LocateZoneRequest.class))).thenReturn(LocateZoneResponse.getDefaultInstance());

        assertThat(locator.locate(39.93, 32.86)).isEmpty();
        assertThat(locator.locate(39.9301, 32.8601)).isEmpty();

        verify(pricing, times(1)).locateZone(any());
    }

    @Test
    void aFailedLookupIsNotCached() {
        when(pricing.locateZone(any(LocateZoneRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .thenReturn(LocateZoneResponse.newBuilder().setZoneCode("Z").build());

        assertThat(locator.locate(41.0, 29.0)).isEmpty();
        assertThat(locator.locate(41.0, 29.0)).contains("Z");

        verify(pricing, times(2)).locateZone(any());
    }

    @Test
    void cachedZonesAreRefreshedAfterTheTtl() {
        when(pricing.locateZone(any(LocateZoneRequest.class)))
                .thenReturn(LocateZoneResponse.newBuilder().setZoneCode("OLD").build())
                .thenReturn(LocateZoneResponse.newBuilder().setZoneCode("NEW").build());

        assertThat(locator.locate(41.0, 29.0)).contains("OLD");
        now.addAndGet(Duration.ofMinutes(59).toMillis());
        assertThat(locator.locate(41.0, 29.0)).contains("OLD");
        now.addAndGet(Duration.ofMinutes(2).toMillis());
        assertThat(locator.locate(41.0, 29.0)).contains("NEW");
    }
}
