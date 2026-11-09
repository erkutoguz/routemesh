package com.routemesh.locationservice.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.routemesh.locationservice.store.CourierLocationStore;

class StaleCourierEvictionJobTest {

    private final CourierLocationStore store = mock(CourierLocationStore.class);
    private final StaleCourierEvictionJob job = new StaleCourierEvictionJob(store);

    @Test
    void evict_delegatesToStore() {
        job.evict();

        verify(store).evictStale();
    }
}
