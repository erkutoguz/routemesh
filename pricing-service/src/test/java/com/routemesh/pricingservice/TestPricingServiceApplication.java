package com.routemesh.pricingservice;

import org.springframework.boot.SpringApplication;

public class TestPricingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(PricingServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
