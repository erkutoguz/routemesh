package com.routemesh.shipmentservice;

import org.springframework.boot.SpringApplication;

public class TestShipmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(ShipmentServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
