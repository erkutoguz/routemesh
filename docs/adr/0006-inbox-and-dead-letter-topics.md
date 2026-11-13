# ADR-0006: Idempotent consumers with an inbox and dead-letter topics

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0002](0002-transactional-outbox-and-debezium-cdc.md), [ADR-0008](0008-postgres-cqrs-search-read-model.md)

## Context

The outbox gives at-least-once delivery, and Kafka consumers can also be rebalanced or restarted mid-record. A consumer
that sends an SMS or writes a read-model row must therefore cope with seeing the same event twice, and with an event it
cannot process at all (malformed JSON, a shipment that no longer exists, a database that is down). One bad record must
not block a partition forever, and a transient outage must not drop records.

## Decision

- **Inbox table.** Consumers with side effects (`search-service`, `notification-service`) keep `processed_events`
  (`event_id` primary key). In **one transaction** the consumer inserts the event id (`ON CONFLICT DO NOTHING`; a conflict
  means "already handled, skip"), performs the effect and writes its own rows. If the effect fails the whole transaction
  rolls back, the inbox row included, so the retry processes the event again. Old inbox rows are removed after 30 days.
- **Classified retries.** A `DefaultErrorHandler` retries transient failures (database, `UNAVAILABLE` gRPC) five times with
  exponential backoff from one to sixteen seconds. Permanent failures are not retried: malformed JSON, invalid ids,
  constraint violations, and gRPC `NOT_FOUND`, `INVALID_ARGUMENT`, `FAILED_PRECONDITION`, `PERMISSION_DENIED`.
- **Dead-letter topics per consumer**, named `<source topic>.<consumer>.dlt` (for example
  `shipment.events.notification-service.dlt`), 14 days retention, created up front by `kafka-init`. The record is
  published with the original payload and the exception details in headers.
- **Ignored events are not recorded.** An event type the consumer does not care about is skipped before the inbox, so the
  inbox only holds events that were actually handled.
- **The code is local to each service**, deliberately not a shared module: the logic is small and the two services differ
  in what "handled" means. It becomes a shared module when a third consumer needs it.
- **Offset policy follows the consumer's nature.** `search-service` reads `earliest` (a read model must be
  rebuildable from the topic); `notification-service` reads `latest` because replaying old events would send real SMS
  and OTPs for shipments that finished long ago.
- **Redelivery is safe even for the OTP.** If the process dies after sending the SMS but before the commit, the retry issues
  a new code and the previous one stops working; the recipient may get two messages and the last one is valid.

## Consequences

Positive:

- Duplicates and crashes are harmless by construction; poison records are isolated instead of stalling the stream.
- Operators can see exactly which records failed and why (the dead-letter topic is a first-class artifact).

Negative:

- Dead-letter records are not replayed automatically and there is no replay tool; the
  [runbook](../runbook.md#2-dead-letter-topics) describes the manual procedure.
- Exhausting the retries on a transient outage drops the event from the consumer's point of view (it lands in the dead-letter
  topic, not in the read model). The workflow relays that consume `shipment.events` have no dead-letter topic: a record they
  cannot process after thirty attempts two seconds apart is skipped.
- A second table and a transaction per event, and an inbox cleanup job.

## Alternatives considered

- **Natural idempotency only** (upserts). Works for the search projection (version-guarded) but not for side effects like SMS.
- **Exactly-once Kafka transactions.** Do not extend to an external SMS provider or a REST/gRPC call.
- **Blocking retry forever.** One poison record would stall a partition.
