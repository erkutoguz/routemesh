package com.routemesh.paymentservice.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.routemesh.paymentservice.config.PaymentProperties;
import com.routemesh.paymentservice.repository.PaymentRepository;

@Component
public class PendingTransactionRetrier {

    private static final Logger log = LoggerFactory.getLogger(PendingTransactionRetrier.class);
    private static final int BATCH_SIZE = 50;

    private final PaymentRepository repository;
    private final PspOperationExecutor executor;
    private final PaymentProperties properties;

    public PendingTransactionRetrier(PaymentRepository repository, PspOperationExecutor executor,
                                     PaymentProperties properties) {
        this.repository = repository;
        this.executor = executor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${routemesh.payment.retry-after}")
    public int retry() {
        int handled = 0;
        for (UUID id : repository.findRetryableTransactionIds(properties.retryAfter().toSeconds(),
                properties.maxAttempts(), BATCH_SIZE)) {
            PspOperationExecutor.Outcome outcome = executor.execute(id);
            log.info("Retried PSP transaction {}: {}", id, outcome);
            handled++;
        }
        return handled;
    }
}
