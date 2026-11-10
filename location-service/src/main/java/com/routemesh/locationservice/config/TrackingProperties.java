package com.routemesh.locationservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "routemesh.tracking")
public record TrackingProperties(
        @DefaultValue("100") double approachRadiusMeters,
        @DefaultValue("41.0082") double depotLatitude,
        @DefaultValue("28.9784") double depotLongitude,
        // Bitiş olayı kaçsa bile izleme kaydı bu süre sonra kendiliğinden silinir
        @DefaultValue("48h") Duration watchTtl,
        // Biten gönderiye ait geç/eski olayın izlemeyi yeniden kurmasını engelleyen işaretin ömrü
        @DefaultValue("24h") Duration doneTtl,
        @DefaultValue("location.events") String approachTopic,
        @DefaultValue("courier.locations") String telemetryTopic,
        @DefaultValue("true") boolean telemetryEnabled,
        // Kafka'ya gönderilmeyi bekleyen en fazla konum sayısı; dolunca yeni konumlar atılır
        @DefaultValue("10000") int telemetryQueueCapacity) {
}
