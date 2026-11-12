package com.routemesh.paymentservice.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("routemesh.auth")
public record AuthProperties(String tokenUri, String clientId, String clientSecret) {
}
