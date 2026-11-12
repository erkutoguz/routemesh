package com.routemesh.paymentservice.ledger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.web.client.RestClient;

import com.routemesh.contracts.ledger.LedgerServiceGrpc;

@Configuration(proxyBeanMethods = false)
class LedgerClientConfiguration {

    @Bean
    ServiceTokenSupplier serviceTokenSupplier(AuthProperties properties) {
        return new ServiceTokenSupplier(properties, RestClient.create());
    }

    @Bean
    LedgerServiceGrpc.LedgerServiceBlockingStub ledgerStub(GrpcChannelFactory channels, ServiceTokenSupplier tokens) {
        return LedgerServiceGrpc.newBlockingStub(channels.createChannel("ledger"))
                .withInterceptors(new BearerTokenAuthenticationInterceptor(tokens));
    }
}
