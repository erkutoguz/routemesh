package com.routemesh.searchservice.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.routemesh.searchservice.projection.EventDispatcher;

@Component
@ConditionalOnProperty(name = "routemesh.search.consumer-enabled", havingValue = "true")
class EventListeners {

    static final String SHIPMENT_EVENTS = "shipment.events";

    private final EventDispatcher dispatcher;

    EventListeners(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @KafkaListener(topics = SHIPMENT_EVENTS)
    void onShipmentEvent(String message) {
        dispatcher.dispatch(SHIPMENT_EVENTS, message);
    }
}
