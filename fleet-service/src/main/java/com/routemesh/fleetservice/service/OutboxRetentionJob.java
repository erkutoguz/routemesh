package com.routemesh.fleetservice.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.routemesh.fleetservice.config.OutboxProperties;
import com.routemesh.fleetservice.repository.FleetRepository;

@Component
public class OutboxRetentionJob {

    static final int BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionJob.class);

    private final FleetRepository repository;
    private final OutboxProperties properties;

    public OutboxRetentionJob(FleetRepository repository, OutboxProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(cron = "${routemesh.outbox.cleanup-cron}")
    public long purge() {
        Instant cutoff = Instant.now().minus(properties.retention());
        long total = 0;
        int deleted;
        do {
            deleted = repository.deleteOutboxOlderThan(cutoff, BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        if (total > 0) {
            log.info("Deleted {} outbox events older than {}", total, cutoff);
        }
        return total;
    }
}
