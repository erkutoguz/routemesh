package com.routemesh.pspsimulator;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimulatorConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
