package com.routemesh.workflowservice.rest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Service;

import com.routemesh.workflowservice.config.TemporalProperties;
import com.routemesh.workflowservice.workflow.CheckoutInfo;
import com.routemesh.workflowservice.workflow.ShipmentWorkflow;
import com.routemesh.workflowservice.workflow.ShipmentWorkflowInput;
import com.routemesh.workflowservice.workflow.ShipmentWorkflowResult;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateStage;

@Service
public class ShipmentStarter {

    private static final long CHECKOUT_WAIT_SECONDS = 15;
    private static final long RESULT_WAIT_SECONDS = 5;

    public sealed interface Outcome {
        record CheckoutReady(UUID shipmentId, CheckoutInfo checkout) implements Outcome {
        }

        record Finished(UUID shipmentId, ShipmentWorkflowResult result) implements Outcome {
        }

        record Pending(UUID shipmentId) implements Outcome {
        }
    }

    private final WorkflowClient client;
    private final TemporalProperties properties;

    public ShipmentStarter(WorkflowClient client, TemporalProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public static UUID shipmentIdFor(UUID merchantId, String idempotencyKey) {
        return UUID.nameUUIDFromBytes((merchantId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    public Outcome start(ShipmentWorkflowInput input) {
        String workflowId = "shipment-" + input.shipmentId();
        ShipmentWorkflow typed = client.newWorkflowStub(ShipmentWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(properties.taskQueue())
                .setWorkflowId(workflowId)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        WorkflowStub stub = WorkflowStub.fromTyped(typed);
        try {
            WorkflowClient.start(typed::run, input);
        } catch (WorkflowExecutionAlreadyStarted e) {
            stub = client.newUntypedWorkflowStub(workflowId);
        }
        return awaitCheckout(input.shipmentId(), stub);
    }

    private Outcome awaitCheckout(UUID shipmentId, WorkflowStub stub) {
        try {
            CheckoutInfo checkout = stub.startUpdate(UpdateOptions.newBuilder(CheckoutInfo.class)
                            .setUpdateName("awaitCheckout")
                            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                            .build())
                    .getResultAsync(CHECKOUT_WAIT_SECONDS, TimeUnit.SECONDS)
                    .get();
            if (checkout.paid()) {
                return new Outcome.Finished(shipmentId,
                        new ShipmentWorkflowResult(ShipmentWorkflowResult.PAID, null, checkout.paymentId()));
            }
            return new Outcome.CheckoutReady(shipmentId, checkout);
        } catch (ExecutionException e) {
            return finishedOrFail(shipmentId, stub, e.getCause());
        } catch (WorkflowUpdateException e) {
            return finishedOrFail(shipmentId, stub, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome.Pending(shipmentId);
        } catch (RuntimeException e) {
            return finishedOrFail(shipmentId, stub, e);
        }
    }

    private Outcome finishedOrFail(UUID shipmentId, WorkflowStub stub, Throwable cause) {
        if (cause instanceof TimeoutException) {
            return new Outcome.Pending(shipmentId);
        }
        try {
            return new Outcome.Finished(shipmentId,
                    stub.getResult(RESULT_WAIT_SECONDS, TimeUnit.SECONDS, ShipmentWorkflowResult.class));
        } catch (TimeoutException | RuntimeException e) {
            throw new IllegalStateException("shipment workflow is not available", cause);
        }
    }
}
