package com.routemesh.paymentservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "routemesh.outbox")
public record OutboxProperties(@DefaultValue("7d") Duration retention) {
}
