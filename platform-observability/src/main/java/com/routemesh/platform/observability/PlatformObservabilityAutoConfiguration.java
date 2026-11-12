package com.routemesh.platform.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

@AutoConfiguration
public class PlatformObservabilityAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaRecordReceiverContext.class)
    static class KafkaConfiguration {

        @Bean
        EventIdObservationFilter eventIdObservationFilter() {
            return new EventIdObservationFilter();
        }
    }
}
