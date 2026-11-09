package com.routemesh.pricingservice.domain;

public record ServiceLevelConfig(ServiceLevel level, int multiplierBps, int slaMinutes) {
}
