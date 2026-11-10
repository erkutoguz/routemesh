package com.routemesh.locationservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"spring.grpc.server.port=0", "routemesh.tracking.consumer-enabled=false",
        "routemesh.tracking.telemetry-enabled=false"})
class LocationServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
