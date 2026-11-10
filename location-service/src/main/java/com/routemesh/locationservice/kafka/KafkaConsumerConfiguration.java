package com.routemesh.locationservice.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import tools.jackson.core.JacksonException;

@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfiguration {

    /** shipment-service geçici erişilemezse aynı olayı 2 sn arayla 30 kez dener; bozuk olay hiç denenmeden atlanır. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(2_000, 30));
        handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class);
        return handler;
    }
}
