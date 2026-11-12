package com.routemesh.notificationservice.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.routemesh.notificationservice.handler.EventDispatcher;

@Component
@ConditionalOnProperty(name = "routemesh.notification.consumer-enabled", havingValue = "true")
class EventListeners {

    static final String SHIPMENT_EVENTS = "shipment.events";
    static final String FLEET_EVENTS = "fleet.events";
    static final String LOCATION_EVENTS = "location.events";

    private final EventDispatcher dispatcher;

    EventListeners(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @KafkaListener(topics = SHIPMENT_EVENTS)
    void onShipmentEvent(String message) {
        dispatcher.dispatch(SHIPMENT_EVENTS, message);
    }

    @KafkaListener(topics = FLEET_EVENTS)
    void onFleetEvent(String message) {
        dispatcher.dispatch(FLEET_EVENTS, message);
    }

    @KafkaListener(topics = LOCATION_EVENTS)
    void onLocationEvent(String message) {
        dispatcher.dispatch(LOCATION_EVENTS, message);
    }
}
