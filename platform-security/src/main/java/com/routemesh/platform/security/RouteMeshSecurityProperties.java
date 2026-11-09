package com.routemesh.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("routemesh.security")
public record RouteMeshSecurityProperties(
        String issuer,
        String jwkSetUri,
        @DefaultValue("routemesh-api") String audience) {
}
