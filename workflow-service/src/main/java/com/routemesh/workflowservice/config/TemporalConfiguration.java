package com.routemesh.workflowservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.routemesh.workflowservice.activity.ShipmentActivities;
import com.routemesh.workflowservice.workflow.ShipmentWorkflowImpl;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

@Configuration(proxyBeanMethods = false)
class TemporalConfiguration {

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs(TemporalProperties properties) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(properties.target()).build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, TemporalProperties properties) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(properties.namespace()).build());
    }

    @Bean
    @ConditionalOnProperty(name = "routemesh.temporal.worker-enabled", havingValue = "true")
    SmartLifecycle shipmentWorker(WorkflowClient client, TemporalProperties properties,
            ShipmentActivities activities) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(properties.taskQueue());
        worker.registerWorkflowImplementationTypes(ShipmentWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        return new SmartLifecycle() {
            @Override
            public void start() {
                factory.start();
            }

            @Override
            public void stop() {
                factory.shutdown();
                factory.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            }

            @Override
            public boolean isRunning() {
                return factory.isStarted() && !factory.isShutdown();
            }
        };
    }
}
