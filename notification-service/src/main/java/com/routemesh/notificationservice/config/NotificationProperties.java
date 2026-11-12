package com.routemesh.notificationservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("routemesh.notification")
public record NotificationProperties(
        boolean consumerEnabled,
        String dltSuffix,
        int retryAttempts,
        Duration retryInitialInterval,
        Duration retryMaxInterval,
        Duration inboxRetention,
        String inboxCleanupCron,
        Sms sms) {

    public record Sms(boolean logBody) {
    }
}
