package com.routemesh.shipmentservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource")
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(
                DockerImageName.parse("postgis/postgis:16-3.7-alpine").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("shipment")
                .withUsername("shipment_owner")
                .withPassword("shipment_owner_dev")
                .withInitScript("test-roles.sql");
    }

}
