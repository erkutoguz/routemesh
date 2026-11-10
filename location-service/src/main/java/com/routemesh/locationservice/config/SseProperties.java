package com.routemesh.locationservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "routemesh.tracking.sse")
public record SseProperties(
                @DefaultValue("5") int maxPerUser,
                @DefaultValue("1000") int maxTotal,
                @DefaultValue("15s") Duration heartbeat,
                @DefaultValue("2s") Duration minInterval,
                @DefaultValue("30m") Duration maxDuration,
                @DefaultValue("5s") Duration healthInterval,
                @DefaultValue("500ms") Duration tick) {
}
