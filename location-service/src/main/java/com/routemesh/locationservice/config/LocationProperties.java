package com.routemesh.locationservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "routemesh.location")
public record LocationProperties(
        @DefaultValue("60s") Duration staleAfter,
        @DefaultValue("24h") Duration stateTtl,
        @DefaultValue("20") int defaultLimit,
        @DefaultValue("100") int maxLimit,
        @DefaultValue("15000") double maxRadiusMeters) {
}
