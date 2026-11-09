package com.routemesh.locationservice;

import org.springframework.boot.SpringApplication;

public class TestLocationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(LocationServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
