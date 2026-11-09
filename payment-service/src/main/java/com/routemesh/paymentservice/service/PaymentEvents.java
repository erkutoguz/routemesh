package com.routemesh.paymentservice.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.routemesh.contracts.events.EventEnvelope;
import com.routemesh.contracts.events.PaymentStatusChangedEvent;
import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.domain.PaymentStatus;
import com.routemesh.paymentservice.repository.PaymentRepository;

import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentEvents {

    private static final String AGGREGATE_TYPE = "payment";

    private final PaymentRepository repository;
    private final JsonMapper jsonMapper;

    public PaymentEvents(PaymentRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    public void statusChanged(PaymentIntent updated, PaymentStatus from, String reason) {
        UUID eventId = UUID.randomUUID();
        PaymentStatusChangedEvent data = new PaymentStatusChangedEvent(updated.id(), updated.shipmentId(),
                from.name(), updated.status().name(), updated.amountMinor(), updated.refundedMinor(),
                updated.currency(), reason, updated.version());
        EventEnvelope<PaymentStatusChangedEvent> envelope = new EventEnvelope<>(eventId,
                PaymentStatusChangedEvent.TYPE, PaymentStatusChangedEvent.VERSION, Instant.now(),
                updated.id().toString(), data);
        repository.insertOutbox(eventId, AGGREGATE_TYPE, updated.id().toString(), PaymentStatusChangedEvent.TYPE,
                jsonMapper.writeValueAsString(envelope));
    }
}
