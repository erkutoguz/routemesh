package com.routemesh.searchservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("routemesh.search")
public record SearchProperties(
        boolean consumerEnabled,
        String dltSuffix,
        int retryAttempts,
        Duration retryInitialInterval,
        Duration retryMaxInterval,
        Duration inboxRetention,
        String inboxCleanupCron) {
}
