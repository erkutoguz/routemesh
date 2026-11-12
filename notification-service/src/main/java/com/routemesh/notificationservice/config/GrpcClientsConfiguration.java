package com.routemesh.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.web.client.RestClient;

import com.routemesh.contracts.shipment.ShipmentServiceGrpc;

@Configuration(proxyBeanMethods = false)
class GrpcClientsConfiguration {

    @Bean
    ServiceTokenSupplier serviceTokenSupplier(AuthProperties properties) {
        return new ServiceTokenSupplier(properties, RestClient.create());
    }

    @Bean
    ShipmentServiceGrpc.ShipmentServiceBlockingStub shipmentStub(GrpcChannelFactory channels,
            ServiceTokenSupplier tokens) {
        return ShipmentServiceGrpc.newBlockingStub(channels.createChannel("shipment"))
                .withInterceptors(new BearerTokenAuthenticationInterceptor(tokens));
    }
}
