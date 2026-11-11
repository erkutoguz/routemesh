package com.routemesh.shipmentservice.storage;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestPodStorageConfiguration {

    @Bean
    @Primary
    InMemoryPodStorage inMemoryPodStorage() {
        return new InMemoryPodStorage();
    }
}
