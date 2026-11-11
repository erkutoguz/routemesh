package com.routemesh.shipmentservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Teslimat kanıtı ayarları ({@code routemesh.pod.*}). 50 m ve 5 MB sınırları kuraldır, ayar değil. */
@ConfigurationProperties(prefix = "routemesh.pod")
public record PodProperties(
        // Bir gönderiye en çok bu kadar kanıt dosyası yüklenebilir (fotoğraf + imza denemeleri dahil)
        @DefaultValue("5") int maxFilesPerShipment,
        @DefaultValue Otp otp,
        @DefaultValue Storage storage) {

    public record Otp(
            @DefaultValue("24h") Duration ttl,
            // Kodları HMAC'lemek için sunucu sırrı; en az 16 karakter olmalı
            String secret) {
    }

    public record Storage(
            String endpoint,
            @DefaultValue("routemesh-pod") String bucket,
            @DefaultValue("us-east-1") String region,
            String accessKey,
            String secretKey) {
    }
}
