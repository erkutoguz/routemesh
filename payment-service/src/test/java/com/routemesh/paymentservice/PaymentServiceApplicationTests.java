package com.routemesh.paymentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import({TestcontainersConfiguration.class, TestPspConfiguration.class})
@SpringBootTest(properties = "spring.grpc.server.port=0")
class PaymentServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
