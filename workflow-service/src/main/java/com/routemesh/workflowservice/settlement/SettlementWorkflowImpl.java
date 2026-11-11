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
    public SettlementResult run(SettlementInput input) {
        UUID shipmentId = input.shipmentId();
        SettlementContext context = activities.load(shipmentId, input.kind());
        long amount = context.amountMinor();

        return switch (input.kind()) {
            case DELIVERY -> delivery(shipmentId, context);
            case RETURN -> {
                SettlementTemplates.Plan plan = SettlementTemplates.returnSettlement(
                        UUID.fromString(context.courierId()), amount);
                settle(shipmentId, plan, "DELIVERY_RETURNED");
                long courier = SettlementTemplates.split(amount).courierMinor();
                yield new SettlementResult(shipmentId.toString(), input.kind(), 0, courier,
                        amount - courier - plan.refundToCustomerMinor(), plan.refundToCustomerMinor(), 0);
            }
            case LOST -> {
                SettlementTemplates.Plan plan = SettlementTemplates.lostSettlement(
                        UUID.fromString(context.merchantId()), amount);
                settle(shipmentId, plan, "SHIPMENT_LOST");
                yield new SettlementResult(shipmentId.toString(), input.kind(),
                        SettlementTemplates.split(amount).merchantMinor(), 0, 0, plan.refundToCustomerMinor(), 0);
            }
            case CANCEL -> {
                SettlementTemplates.Plan plan = SettlementTemplates.cancelRefund(amount);
                settle(shipmentId, plan, "SHIPMENT_CANCELLED");
                yield new SettlementResult(shipmentId.toString(), input.kind(), 0, 0, 0,
                        plan.refundToCustomerMinor(), 0);
            }
        };
    }

    private SettlementResult delivery(UUID shipmentId, SettlementContext context) {
        long amount = context.amountMinor();
        SettlementTemplates.Plan settlement = SettlementTemplates.deliverySettlement(
                UUID.fromString(context.merchantId()), UUID.fromString(context.courierId()), amount);
        activities.postLedger(shipmentId, settlement.transactionType(), settlement.lines());

        long penalty = SettlementTemplates.slaPenalty(amount, Duration.ofMinutes(context.slaMinutes()),
                Duration.ofMillis(context.deliveredAtMillis() - context.warehouseReceivedAtMillis()));
        if (penalty > 0) {
            settle(shipmentId, SettlementTemplates.slaPenaltyRefund(penalty), "SLA_PENALTY");
        }

        SettlementTemplates.Split split = SettlementTemplates.split(amount);
        return new SettlementResult(shipmentId.toString(), SettlementKind.DELIVERY, split.merchantMinor(),
                split.courierMinor(), split.platformMinor() - penalty, penalty, penalty);
    }

    private void settle(UUID shipmentId, SettlementTemplates.Plan plan, String refundReason) {
        activities.postLedger(shipmentId, plan.transactionType(), plan.lines());
        if (plan.refundToCustomerMinor() > 0) {
            activities.refundPayment(shipmentId, plan.transactionType(), plan.refundToCustomerMinor(), refundReason);
        }
    }
}
