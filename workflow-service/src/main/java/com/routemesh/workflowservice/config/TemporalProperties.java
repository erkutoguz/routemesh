package com.routemesh.workflowservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("routemesh.temporal")
public record TemporalProperties(String target, String namespace, String taskQueue, boolean workerEnabled) {
}
