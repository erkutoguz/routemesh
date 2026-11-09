package com.routemesh.pspsimulator;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "routemesh.sim")
public record SimulatorProperties(
        @DefaultValue("whsec_dev_routemesh") String webhookSecret,
        @DefaultValue({"1s", "5s", "25s"}) List<Duration> retryDelays) {
}
