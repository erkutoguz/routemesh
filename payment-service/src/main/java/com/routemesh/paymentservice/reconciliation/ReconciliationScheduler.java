package com.routemesh.paymentservice.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.routemesh.paymentservice.reconciliation.ReconciliationService.RunAlreadyActiveException;
import com.routemesh.paymentservice.reconciliation.ReconciliationService.Trigger;

@Component
@ConditionalOnProperty(name = "routemesh.payment.reconciliation.scheduler-enabled", havingValue = "true")
class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService service;

    ReconciliationScheduler(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${routemesh.payment.reconciliation.interval}",
            initialDelayString = "${routemesh.payment.reconciliation.initial-delay}")
    void reconcile() {
        try {
            service.run(Trigger.SCHEDULED, null, null, null);
        } catch (RunAlreadyActiveException e) {
            log.debug("Skipping scheduled reconciliation: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("RECONCILIATION_SCHEDULE_FAILED", e);
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    void reapStaleRuns() {
        int failed = service.failStaleRuns();
        if (failed > 0) {
            log.warn("Marked {} stale reconciliation run(s) as FAILED", failed);
        }
    }
}
