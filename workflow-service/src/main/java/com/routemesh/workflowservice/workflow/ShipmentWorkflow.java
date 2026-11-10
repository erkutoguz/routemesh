package com.routemesh.workflowservice.workflow;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ShipmentWorkflow {

    @WorkflowMethod
    ShipmentWorkflowResult run(ShipmentWorkflowInput input);

    @UpdateMethod(name = "awaitCheckout")
    CheckoutInfo awaitCheckout();

    /**
     * Gönderi READY_FOR_DISPATCH oldu. {@code shipmentVersion} olayın gönderi sürümüdür; aynı ya da eski
     * sürümle gelen tekrarlar (Kafka yeniden teslimi) yok sayılır.
     */
    @SignalMethod(name = "readyForDispatch")
    void readyForDispatch(long shipmentVersion);
}
