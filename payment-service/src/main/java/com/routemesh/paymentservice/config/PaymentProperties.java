package com.routemesh.paymentservice.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "routemesh.payment")
public record PaymentProperties(
        @DefaultValue("http://localhost:8085") String pspBaseUrl,
        @DefaultValue("http://localhost:8084/api/v1/webhooks/psp") String webhookUrl,
        List<String> webhookSecrets,
        @DefaultValue("5m") Duration webhookTolerance,
        @DefaultValue("15m") Duration intentTtl,
        @DefaultValue("1m") Duration expiryGrace,
        @DefaultValue("30s") Duration retryAfter,
        @DefaultValue("10") int maxAttempts) {
}
