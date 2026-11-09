package com.routemesh.pricingservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "routemesh.pricing")
public record PricingProperties(
        @DefaultValue("10m") Duration quoteTtl,
        @DefaultValue("25000") int maxSurgeBps) {
}
