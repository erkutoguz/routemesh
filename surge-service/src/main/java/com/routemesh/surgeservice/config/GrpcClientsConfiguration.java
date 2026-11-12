package com.routemesh.surgeservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.web.client.RestClient;

import com.routemesh.contracts.pricing.PricingServiceGrpc;

@Configuration(proxyBeanMethods = false)
class GrpcClientsConfiguration {

    @Bean
    ServiceTokenSupplier serviceTokenSupplier(AuthProperties properties) {
        return new ServiceTokenSupplier(properties, RestClient.create());
    }

    @Bean
    PricingServiceGrpc.PricingServiceBlockingStub pricingStub(GrpcChannelFactory channels,
            ServiceTokenSupplier tokens) {
        return PricingServiceGrpc.newBlockingStub(channels.createChannel("pricing"))
                .withInterceptors(new BearerTokenAuthenticationInterceptor(tokens));
    }
}
