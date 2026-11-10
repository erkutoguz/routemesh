package com.routemesh.workflowservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Dağıtım ayarları. Tek depo varsayımı: kurye araması depo konumuna göre yapılır. */
@ConfigurationProperties("routemesh.dispatch")
public record DispatchProperties(
        @DefaultValue("41.0082") double depotLatitude,
        @DefaultValue("28.9784") double depotLongitude,
        @DefaultValue("1000") long defaultWeightGrams,
        @DefaultValue("50") int candidateLimit) {
}
