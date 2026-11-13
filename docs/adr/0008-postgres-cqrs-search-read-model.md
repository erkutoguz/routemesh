# ADR-0008: A Postgres CQRS read model for search

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0002](0002-transactional-outbox-and-debezium-cdc.md), [ADR-0006](0006-inbox-and-dead-letter-topics.md), [ADR-0007](0007-keyset-cursor-pagination.md)

## Context

Merchants list and filter their shipments, ops browses all of them, the warehouse looks at what is waiting. These queries
cut across the write model's access pattern, are read far more than written, and must not load the transactional
`shipment-service` database. We also wanted an audit trail and a dedicated search engine at some point.

## Decision

- **Separate read model in its own service and database** (`search-service`, Postgres `search`), built only from events
  on `shipment.events` (`ShipmentCreated`, `ShipmentStatusChanged`). It never calls the write side.
- **Convergence regardless of arrival order.** Events can be redelivered and, across topics or after replays, arrive in
  any order. The projection is therefore an upsert keyed by shipment id and guarded by the shipment version: a status event
  is applied only if its version is newer than the stored one, the version never goes backwards, `delivered_at` is kept
  from the first `DELIVERED` even if a later version arrives first, and a `ShipmentCreated` that arrives after a status
  event only fills in the missing fields. A test applies a creation event plus three status events in all 24 orders and
  asserts the same final state.
- **Skeleton rows.** A status event that arrives before the creation event opens a skeleton row (`merchant_id`,
  amount and currency all null, enforced by a `CHECK`). Every list query requires `merchant_id IS NOT NULL`, so skeletons
  are invisible; a small partial index makes the skeleton count cheap to expose as a gauge.
- **Query shape.** Composite indexes `(merchant_id, created_at DESC, id DESC)`, `(status, ...)`, `(courier_id, ...)` and a
  partial listing index serve the filters, with keyset pagination ([ADR-0007](0007-keyset-cursor-pagination.md)). Roles:
  `MERCHANT` sees only their own (an explicit other `merchantId` is `403`), `OPS` sees everything, `WAREHOUSE` a status-bounded view.
- **The inbox and dead-letter topic apply** ([ADR-0006](0006-inbox-and-dead-letter-topics.md)); the consumer reads `earliest`.
- **Postgres instead of Elasticsearch for now.** The queries are equality and range filters on a handful of columns,
  which a composite B-tree index handles; running a search cluster would be cost without benefit.

## Consequences

Positive:

- Reads are isolated from the write path, schema and indexes are tuned for the queries, and the model can be rebuilt by
  replaying the topic.
- Out-of-order and duplicate events cannot corrupt it.

Negative:

- Eventual consistency: a list can lag the source by the time it takes an event to traverse Debezium and Kafka (well
  under a second in our end-to-end test).
- History is bounded by the topic retention: shipments older than the retention at the time the consumer first runs are
  absent or only skeletons. Back-filling needs a separate mechanism.
- No free-text search; adding it would mean Postgres text search or a search engine behind the same events.

## Alternatives considered

- **Query the write database through a read replica.** Couples read patterns to the write schema.
- **Elasticsearch.** Powerful, but unnecessary for these filters; revisit for free text or the audit trail.
- **Synchronous calls from the gateway to several services.** Slow and fragile for list screens.
