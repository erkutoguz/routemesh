package com.routemesh.workflowservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Workflow;

/**
 * Her iş akışı koşusunu sayar. İş akışı kodu belirlenimci olmak zorunda olduğundan sayaç iş akışının içinde değil
 * burada, ve yalnızca yeniden oynatma olmayan gerçek yürütmede artırılır.
 * <ul>
 *   <li>{@code routemesh.workflow.started{type}}</li>
 *   <li>{@code routemesh.workflow.finished{type,outcome}} — outcome: completed | failed | canceled</li>
 * </ul>
 * {@code type} iş akışı arayüzünün adıdır (ShipmentWorkflow, DispatchWorkflow, SettlementWorkflow); sabit bir kümedir.
 */
public class WorkflowMetricsInterceptor extends WorkerInterceptorBase {

    private final MeterRegistry meters;

    public WorkflowMetricsInterceptor(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
        return new WorkflowInboundCallsInterceptorBase(next) {
            @Override
            public WorkflowOutput execute(WorkflowInput input) {
                String type = Workflow.getInfo().getWorkflowType();
                if (!Workflow.isReplaying()) {
                    meters.counter("routemesh.workflow.started", "type", type).increment();
                }
                try {
                    WorkflowOutput output = super.execute(input);
                    finished(type, "completed");
                    return output;
                } catch (CanceledFailure e) {
                    finished(type, "canceled");
                    throw e;
                } catch (RuntimeException e) {
                    finished(type, "failed");
                    throw e;
                }
            }
        };
    }

    private void finished(String type, String outcome) {
        if (!Workflow.isReplaying()) {
            meters.counter("routemesh.workflow.finished", "type", type, "outcome", outcome).increment();
        }
    }
}
