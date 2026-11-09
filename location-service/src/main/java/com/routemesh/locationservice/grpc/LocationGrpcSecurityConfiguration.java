package com.routemesh.locationservice.grpc;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;

@Configuration(proxyBeanMethods = false)
class LocationGrpcSecurityConfiguration {

    private static final String STREAM = "routemesh.location.v1.LocationService/StreamCourierLocations";
    private static final String NEAREST = "routemesh.location.v1.LocationService/FindNearestCouriers";

    @Bean
    @GlobalServerInterceptor
    AuthenticationProcessInterceptor locationAuthenticationInterceptor(GrpcSecurity grpcSecurity) throws Exception {
        grpcSecurity.authorizeRequests(requests -> {
            requests.methods(STREAM).hasAuthority("ROLE_COURIER");
            requests.methods(NEAREST).hasAuthority("ROLE_WORKFLOW");
            requests.allRequests().denyAll();
        });
        grpcSecurity.oauth2ResourceServer(resourceServer -> resourceServer.jwt(withDefaults()));
        return grpcSecurity.build();
    }
}
