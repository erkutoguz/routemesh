package com.routemesh.workflowservice.workflow;

import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ShipmentWorkflow {

    @WorkflowMethod
    ShipmentWorkflowResult run(ShipmentWorkflowInput input);

    @UpdateMethod(name = "awaitCheckout")
    CheckoutInfo awaitCheckout();
}
