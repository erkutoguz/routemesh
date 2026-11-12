package com.routemesh.workflowservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.routemesh.workflowservice.activity.DispatchActivities;
import com.routemesh.workflowservice.activity.ShipmentActivities;
import com.routemesh.workflowservice.settlement.SettlementActivities;
import com.routemesh.workflowservice.settlement.SettlementWorkflowImpl;
import com.routemesh.workflowservice.workflow.DispatchWorkflowImpl;
import com.routemesh.workflowservice.workflow.ShipmentWorkflowImpl;

import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;

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
            ShipmentActivities activities, DispatchActivities dispatchActivities,
            SettlementActivities settlementActivities, MeterRegistry meters) {
        WorkerFactory factory = WorkerFactory.newInstance(client, WorkerFactoryOptions.newBuilder()
                .setWorkerInterceptors(new WorkflowMetricsInterceptor(meters)).build());
        Worker worker = factory.newWorker(properties.taskQueue());
        worker.registerWorkflowImplementationTypes(ShipmentWorkflowImpl.class, DispatchWorkflowImpl.class,
                SettlementWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities, dispatchActivities, settlementActivities);
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
