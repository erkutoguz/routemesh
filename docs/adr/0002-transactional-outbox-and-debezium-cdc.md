# ADR-0002: Transactional outbox published by Debezium CDC

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0006](0006-inbox-and-dead-letter-topics.md), [ADR-0008](0008-postgres-cqrs-search-read-model.md)

## Context

Services must tell the rest of the platform what happened (a shipment changed status, a ledger transaction was posted,
an offer was accepted). Writing to the database and then to Kafka is a dual write: a crash between the two either loses
the event or publishes one for a change that rolled back. We also do not want every service to run its own
polling loop over its events table.

## Decision

- A service never produces to Kafka directly. In the **same database transaction** as the state change it inserts a row
  into its own `outbox_events` table (id, aggregate type, aggregate id, event type, JSON payload).
- **Debezium** (Kafka Connect, `pgoutput`) reads the WAL of each outbox table through a publication that publishes
  `INSERT` only, and its `EventRouter` transform routes each row to the topic `<aggregate_type>.events`, keyed by
  `aggregate_id`, with the event id and type as headers. One connector per outbox service: `shipment-outbox`,
  `ledger-outbox`, `payment-outbox`, `fleet-outbox`. Snapshots are off (`no_data`) and the publication is created by the
  service's Flyway migration, never by the connector.
- The payload is an `EventEnvelope` (`eventId`, `eventType`, `eventVersion`, `occurredAt`, `aggregateId`, `data`) defined
  in `contracts`. Events are facts with a stable, versioned shape.
- Outbox rows are kept seven days and removed by a daily job in batches. Deletes never reach Kafka because the
  publication does not include them.
- The one exception is `location-service`, which has no relational database: it writes `ApproachingDestination`
  straight to `location.events` (with a deterministic event id so consumers can deduplicate and a re-arming step when the
  send fails) and lossy telemetry to `courier.locations`.

## Consequences

Positive:

- Atomic by construction: an event exists if and only if the state change committed. No relay process to keep alive,
  no polling interval to tune.
- Per-aggregate ordering comes for free from keying by aggregate id.
- Consumers get replay: a new consumer group reads the topic from the start (retention is seven days).

Negative:

- At-least-once delivery, so every consumer has to be idempotent ([ADR-0006](0006-inbox-and-dead-letter-topics.md)).
- Debezium needs `wal_level=logical`, a replication slot per connector (with `max_slot_wal_keep_size` as a safety
  net) and an operator who knows that a connector registered before the migration ran fails until restarted.
- Trace context does not survive the outbox: Debezium does not carry it. Consumer spans start a new trace and carry the
  outbox `eventId` as `routemesh.event.id` so the two sides can still be joined.
- Events older than the topic retention are gone; a consumer introduced later cannot back-fill history from Kafka alone.

## Alternatives considered

- **Dual write with retries.** Loses or duplicates events under failure.
- **Polling outbox relay in each service.** Works, but is more code per service and adds latency and database load.
- **Event sourcing.** A much bigger commitment than the problem requires; the ledger is the only place where the log
  is the truth, and it has its own design ([ADR-0003](0003-immutable-double-entry-ledger.md)).
