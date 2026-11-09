package com.routemesh.pspsimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class PspSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PspSimulatorApplication.class, args);
    }

}
