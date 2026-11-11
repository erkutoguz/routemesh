package com.routemesh.workflowservice.settlement;

import java.time.Duration;
import java.util.UUID;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

public class SettlementWorkflowImpl implements SettlementWorkflow {

    // Para hareketi sonuçlanana kadar sonsuz denenir; iş hataları (REJECTED) yeniden denenmez ve iş akışını düşürür
    private final SettlementActivities activities = Workflow.newActivityStub(SettlementActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .build())
                    .build());

    @Override
    public SettlementResult run(UUID shipmentId) {
        SettlementContext context = activities.load(shipmentId);
        UUID merchantId = UUID.fromString(context.merchantId());
        UUID courierId = UUID.fromString(context.courierId());

        SettlementTemplates.Plan settlement = SettlementTemplates.deliverySettlement(merchantId, courierId,
                context.amountMinor());
        activities.postLedger(shipmentId, settlement.transactionType(), settlement.lines());

        long penalty = SettlementTemplates.slaPenalty(context.amountMinor(),
                Duration.ofMinutes(context.slaMinutes()),
                Duration.ofMillis(context.deliveredAtMillis() - context.warehouseReceivedAtMillis()));
        if (penalty > 0) {
            SettlementTemplates.Plan refund = SettlementTemplates.slaPenaltyRefund(penalty);
            activities.postLedger(shipmentId, refund.transactionType(), refund.lines());
            activities.refundPayment(shipmentId, refund.transactionType(), refund.refundToCustomerMinor(),
                    "SLA_PENALTY");
        }

        SettlementTemplates.Split split = SettlementTemplates.split(context.amountMinor());
        return new SettlementResult(shipmentId.toString(), split.merchantMinor(), split.courierMinor(),
                split.platformMinor(), penalty);
    }
}
