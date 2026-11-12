package com.routemesh.paymentservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestPspConfiguration {

    @Bean
    @Primary
    FakePspClient fakePspClient() {
        return new FakePspClient();
    }

    @Bean
    @Primary
    FakeLedgerClearingClient fakeLedgerClearingClient(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new FakeLedgerClearingClient(jdbc);
    }
}
