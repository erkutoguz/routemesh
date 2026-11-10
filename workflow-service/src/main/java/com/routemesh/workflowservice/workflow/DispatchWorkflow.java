package com.routemesh.workflowservice.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface DispatchWorkflow {

    @WorkflowMethod
    DispatchResult dispatch(DispatchInput input);
}
