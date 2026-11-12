package com.routemesh.workflowservice.config;

import org.springframework.beans.factory.ObjectProvider;
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
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
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

    /**
     * İz bağlamını Temporal başlıklarıyla taşır: istek -> iş akışı -> etkinlik (gRPC) tek iz olur. Temporal'in
     * OpenTracing arayüzü OpenTelemetry'ye shim ile bağlanır; OpenTelemetry yoksa (izleme kapalı bağlam) atlanır.
     */
    @Bean
    OpenTracingOptions temporalTracingOptions(ObjectProvider<OpenTelemetry> openTelemetry) {
        OpenTelemetry otel = openTelemetry.getIfAvailable();
        return otel == null ? null
                : OpenTracingOptions.newBuilder().setTracer(OpenTracingShim.createTracerShim(otel)).build();
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, TemporalProperties properties,
            ObjectProvider<OpenTracingOptions> tracing) {
        WorkflowClientOptions.Builder options = WorkflowClientOptions.newBuilder().setNamespace(properties.namespace());
        OpenTracingOptions tracingOptions = tracing.getIfAvailable();
        if (tracingOptions != null) {
            options.setInterceptors(new OpenTracingClientInterceptor(tracingOptions));
        }
        return WorkflowClient.newInstance(stubs, options.build());
    }

    @Bean
    @ConditionalOnProperty(name = "routemesh.temporal.worker-enabled", havingValue = "true")
    SmartLifecycle shipmentWorker(WorkflowClient client, TemporalProperties properties,
            ShipmentActivities activities, DispatchActivities dispatchActivities,
            SettlementActivities settlementActivities, MeterRegistry meters, ObjectProvider<OpenTracingOptions> tracing) {
        OpenTracingOptions tracingOptions = tracing.getIfAvailable();
        WorkerInterceptor[] interceptors = tracingOptions == null
                ? new WorkerInterceptor[] {new WorkflowMetricsInterceptor(meters)}
                : new WorkerInterceptor[] {new OpenTracingWorkerInterceptor(tracingOptions),
                        new WorkflowMetricsInterceptor(meters)};
        WorkerFactory factory = WorkerFactory.newInstance(client,
                WorkerFactoryOptions.newBuilder().setWorkerInterceptors(interceptors).build());
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
