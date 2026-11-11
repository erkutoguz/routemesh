package com.routemesh.workflowservice.settlement;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Gönderi sonlandığında escrow'daki tutarı şablona göre dağıtır: teslimat, iade, kayıp ya da iptal. Müşteriye gidecek
 * tutar PSP üzerinden iade edilir.
 */
@WorkflowInterface
public interface SettlementWorkflow {

    @WorkflowMethod
    SettlementResult run(SettlementInput input);
}
