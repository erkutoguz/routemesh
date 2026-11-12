package com.routemesh.ledgerservice.integrity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IntegrityConfiguration {

    /** Elle tetiklenen FULL denetimler istek iş parçacığını tutmasın diye tek iş parçacıklı arka plan yürütücüsü. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService integrityExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ledger-integrity");
            thread.setDaemon(true);
            return thread;
        });
    }
}
