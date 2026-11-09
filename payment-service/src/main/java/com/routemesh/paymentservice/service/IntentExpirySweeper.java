package com.routemesh.paymentservice.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.routemesh.paymentservice.config.PaymentProperties;
import com.routemesh.paymentservice.repository.PaymentRepository;

@Component
public class IntentExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(IntentExpirySweeper.class);
    private static final int BATCH_SIZE = 100;

    private final PaymentRepository repository;
    private final PaymentService paymentService;
    private final PaymentProperties properties;

    public IntentExpirySweeper(PaymentRepository repository, PaymentService paymentService,
                               PaymentProperties properties) {
        this.repository = repository;
        this.paymentService = paymentService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${routemesh.payment.sweep-interval:60s}")
    public int sweep() {
        int expired = 0;
        for (UUID id : repository.findExpirableIntentIds(properties.expiryGrace().toSeconds(), BATCH_SIZE)) {
            try {
                paymentService.expire(id);
                expired++;
            } catch (PaymentException e) {
                log.debug("Payment {} was not expired: {}", id, e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Expired {} payments past their window", expired);
        }
        return expired;
    }
}
