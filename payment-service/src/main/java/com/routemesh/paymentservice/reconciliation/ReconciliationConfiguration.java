package com.routemesh.paymentservice.reconciliation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReconciliationConfiguration {

    /** Elle tetiklenen koşular istek iş parçacığını tutmasın diye tek iş parçacıklı arka plan yürütücüsü. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService reconciliationExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "psp-reconciliation");
            thread.setDaemon(true);
            return thread;
        });
    }
}
