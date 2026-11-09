package com.routemesh.platform.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;

import static org.springframework.security.config.Customizer.withDefaults;

@AutoConfiguration(
        afterName = "org.springframework.boot.grpc.server.autoconfigure.security.GrpcServerSecurityAutoConfiguration",
        beforeName = "org.springframework.boot.grpc.server.autoconfigure.security.GrpcServerOAuth2ResourceServerAutoConfiguration")
@ConditionalOnClass(GrpcSecurity.class)
public class PlatformGrpcSecurityAutoConfiguration {

    public static final String WORKFLOW_AUTHORITY = "ROLE_WORKFLOW";

    @Bean
    @GlobalServerInterceptor
    @ConditionalOnBean(GrpcSecurity.class)
    @ConditionalOnMissingBean(AuthenticationProcessInterceptor.class)
    AuthenticationProcessInterceptor workflowOnlyInterceptor(GrpcSecurity grpcSecurity) throws Exception {
        grpcSecurity.authorizeRequests(requests -> requests.allRequests().hasAuthority(WORKFLOW_AUTHORITY));
        grpcSecurity.oauth2ResourceServer(resourceServer -> resourceServer.jwt(withDefaults()));
        return grpcSecurity.build();
    }
}
