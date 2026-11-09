package com.routemesh.locationservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.routemesh.locationservice.store.CourierLocationStore;

@Component
public class StaleCourierEvictionJob {

    private static final Logger log = LoggerFactory.getLogger(StaleCourierEvictionJob.class);

    private final CourierLocationStore store;

    public StaleCourierEvictionJob(CourierLocationStore store) {
        this.store = store;
    }

    @Scheduled(
            initialDelayString = "${routemesh.location.eviction-interval}",
            fixedDelayString = "${routemesh.location.eviction-interval}")
    public void evict() {
        long removed = store.evictStale();
        if (removed > 0) {
            log.info("Evicted {} stale couriers", removed);
        }
    }
}
