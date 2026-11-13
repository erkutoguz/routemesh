# ADR-0004: Orchestrate long-running work with Temporal sagas

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0001](0001-grpc-inside-rest-outside.md), [ADR-0003](0003-immutable-double-entry-ledger.md)

## Context

Creating a shipment touches pricing (redeem a quote), shipment (create), payment (intent, authorization, capture) and the
ledger (fund the escrow), across processes, with a human in the middle (the customer pays whenever they pay). Delivery
settlement, dispatching a courier (offers that expire, couriers that decline) and refunds are the same shape: multi-step,
long-lived, each step can fail or time out, and partial progress has to be undone or resumed. Choreographing this with
events alone scatters the logic across services and makes "what is the state of this shipment's payment flow" unanswerable.

## Decision

- **Temporal orchestrates** the flows that span services. `workflow-service` owns three workflows: `ShipmentWorkflow`
  (quote redemption, shipment creation, payment, escrow, `PAID`), `DispatchWorkflow` (find couriers, offer, assign, or
  hold) and `SettlementWorkflow` (post the settlement template, then the customer refund if any). Workflows call services
  over gRPC through activities, using a `WORKFLOW` service-account token.
- **Compensation is a saga** (Temporal `Saga`, errors continued): reverse the escrow, release the payment (expire, void or
  refund the remainder depending on its state), cancel the shipment. Compensations retry forever and run in reverse order.
  `PAID` is the last step, after which a failure is no longer compensated by this workflow.
- **Idempotency is explicit.** Every side-effecting call gets the key `<workflowId>:<operation>` (`redeem`, `create`,
  `intent`, `capture`, `escrow`, `escrow-reversal`, `paid`, ...). It is derived from the operation name rather than the
  activity id because an activity id is a sequence number that shifts when the workflow code evolves, and because a
  compensation must know the key of the forward step it undoes.
- **Retry policy follows the error.** Business rejections are mapped to non-retryable failures so a bad request fails the
  workflow; transient errors retry with backoff, forever for money movements.
- **Determinism is protected.** Metrics are recorded by a worker interceptor that only counts non-replayed executions, not
  inside workflow code. Workflow code evolution uses `Workflow.getVersion` (for example, the dispatch phase after `PAID`).
- **Triggers.** The REST call starts the workflow (the workflow id is derived from the merchant and the
  `Idempotency-Key`, so a retried request attaches to the same run) and answers when the checkout is ready, via a Temporal
  Update. Kafka relays signal or start workflows from `shipment.events`.

## Consequences

Positive:

- The state of a flow is visible in one place (Temporal UI) and survives restarts: a payment completed while
  `workflow-service` was down is picked up on return.
- Timeouts, retries and compensation are code with tests (time-skipping `TestWorkflowEnvironment`), not scattered handlers.

Negative:

- A new infrastructure dependency (Temporal server plus its own Postgres) and a determinism discipline for workflow code.
- A `CreatePaymentIntent` that times out with an unknown outcome leaves an orphan intent that the workflow cannot
  compensate (it has no payment id); `payment-service` closes such intents with an expiry sweeper.
- Workflow input types are part of the contract with running executions; changing one requires draining in-flight runs.
- The shipment workflow stays open after `PAID` and is closed by a terminal status; cancelled shipments leave it waiting
  for a signal that never comes.

## Alternatives considered

- **Choreography over Kafka.** Loosely coupled, but retries, timeouts and compensation end up implicit.
- **A hand-rolled state machine with a jobs table.** Re-implements durable timers, retries and visibility badly.
- **Database sagas inside one service.** Does not fit: the steps live in four different services.
