package com.routemesh.ledgerservice.integrity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.routemesh.ledgerservice.integrity.IntegrityService.Mode;
import com.routemesh.ledgerservice.integrity.IntegrityService.RunAlreadyActiveException;
import com.routemesh.ledgerservice.integrity.IntegrityService.Trigger;

@Component
@ConditionalOnProperty(name = "routemesh.ledger.integrity.scheduler-enabled", havingValue = "true")
class IntegrityScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntegrityScheduler.class);

    private final IntegrityService service;

    IntegrityScheduler(IntegrityService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${routemesh.ledger.integrity.incremental-interval}",
            initialDelayString = "${routemesh.ledger.integrity.incremental-initial-delay}")
    void incremental() {
        runScheduled(Mode.INCREMENTAL);
    }

    @Scheduled(cron = "${routemesh.ledger.integrity.full-cron}")
    void full() {
        runScheduled(Mode.FULL);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    void reapStaleRuns() {
        int failed = service.failStaleRuns();
        if (failed > 0) {
            log.warn("Marked {} stale integrity run(s) as FAILED", failed);
        }
    }

    private void runScheduled(Mode mode) {
        try {
            service.run(mode, Trigger.SCHEDULED, null);
        } catch (RunAlreadyActiveException e) {
            log.debug("Skipping scheduled {} integrity run: {}", mode, e.getMessage());
        } catch (RuntimeException e) {
            log.error("LEDGER_INTEGRITY_SCHEDULE_FAILED mode={}", mode, e);
        }
    }
}
