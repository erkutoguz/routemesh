package com.routemesh.workflowservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.web.client.RestClient;

import com.routemesh.contracts.fleet.FleetServiceGrpc;
import com.routemesh.contracts.ledger.LedgerServiceGrpc;
import com.routemesh.contracts.location.LocationServiceGrpc;
import com.routemesh.contracts.payment.PaymentServiceGrpc;
import com.routemesh.contracts.pricing.PricingServiceGrpc;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;

import io.grpc.ClientInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthProperties.class, TemporalProperties.class, DispatchProperties.class})
class GrpcClientsConfiguration {

    @Bean
    ServiceTokenSupplier serviceTokenSupplier(AuthProperties properties) {
        return new ServiceTokenSupplier(properties, RestClient.create());
    }

    @Bean
    ClientInterceptor workflowTokenInterceptor(ServiceTokenSupplier tokens) {
        return new BearerTokenAuthenticationInterceptor(tokens);
    }

    @Bean
    PricingServiceGrpc.PricingServiceBlockingStub pricingStub(GrpcChannelFactory channels,
            ClientInterceptor workflowTokenInterceptor) {
        return PricingServiceGrpc.newBlockingStub(channels.createChannel("pricing"))
                .withInterceptors(workflowTokenInterceptor);
    }

    @Bean
    ShipmentServiceGrpc.ShipmentServiceBlockingStub shipmentStub(GrpcChannelFactory channels,
            ClientInterceptor workflowTokenInterceptor) {
        return ShipmentServiceGrpc.newBlockingStub(channels.createChannel("shipment"))
                .withInterceptors(workflowTokenInterceptor);
    }

    @Bean
    PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub(GrpcChannelFactory channels,
            ClientInterceptor workflowTokenInterceptor) {
        return PaymentServiceGrpc.newBlockingStub(channels.createChannel("payment"))
                .withInterceptors(workflowTokenInterceptor);
    }

    @Bean
    LocationServiceGrpc.LocationServiceBlockingStub locationStub(GrpcChannelFactory channels,
            ClientInterceptor workflowTokenInterceptor) {
        return LocationServiceGrpc.newBlockingStub(channels.createChannel("location"))
                .withInterceptors(workflowTokenInterceptor);
    }

    @Bean
    FleetServiceGrpc.FleetServiceBlockingStub fleetStub(GrpcChannelFactory channels,
            ClientInterceptor workflowTokenInterceptor) {
        return FleetServiceGrpc.newBlockingStub(channels.createChannel("fleet"))
                .withInterceptors(workflowTokenInterceptor);
    }

    @Bean
    LedgerServiceGrpc.LedgerServiceBlockingStub ledgerStub(GrpcChannelFactory channels,
            ClientInterceptor workflowTokenInterceptor) {
        return LedgerServiceGrpc.newBlockingStub(channels.createChannel("ledger"))
                .withInterceptors(workflowTokenInterceptor);
    }
}
