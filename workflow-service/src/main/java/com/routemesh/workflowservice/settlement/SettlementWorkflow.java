package com.routemesh.workflowservice.settlement;

import java.util.UUID;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Gönderi teslim edildiğinde escrow'daki tutarı dağıtır ve gerekirse SLA cezasını müşteriye iade eder. */
@WorkflowInterface
public interface SettlementWorkflow {

    @WorkflowMethod
    SettlementResult run(UUID shipmentId);
}
