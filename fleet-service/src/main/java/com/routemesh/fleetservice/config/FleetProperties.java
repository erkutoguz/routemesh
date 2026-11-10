package com.routemesh.fleetservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "routemesh.fleet")
public record FleetProperties(
        @DefaultValue("20000") int defaultMaxWeightGrams,
        @DefaultValue("10s") Duration lockTtl,
        @DefaultValue("2s") Duration lockWait,
        @DefaultValue("10m") Duration maxOfferTtl) {
}
