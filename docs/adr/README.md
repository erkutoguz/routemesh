# Architecture decision records

Each record states the context, the decision, its consequences (good and bad) and what was rejected. They are written
for the next engineer who wonders "why is it like this?" and should be amended, not rewritten, when a decision changes: add a
new record and mark the old one `Superseded by ADR-NNNN`.

| # | Decision | Area |
|---|---|---|
| [0001](0001-grpc-inside-rest-outside.md) | gRPC between services, REST and SSE at the edge | Communication |
| [0002](0002-transactional-outbox-and-debezium-cdc.md) | Transactional outbox published by Debezium CDC | Messaging |
| [0003](0003-immutable-double-entry-ledger.md) | An immutable, generic double-entry ledger | Money |
| [0004](0004-temporal-saga-orchestration.md) | Orchestrate long-running work with Temporal sagas | Workflow |
| [0005](0005-zero-trust-jwt-and-ownership-isolation.md) | Zero-trust JWT validation and ownership isolation | Security |
| [0006](0006-inbox-and-dead-letter-topics.md) | Idempotent consumers with an inbox and dead-letter topics | Messaging |
| [0007](0007-keyset-cursor-pagination.md) | Keyset (cursor) pagination for every list endpoint | API |
| [0008](0008-postgres-cqrs-search-read-model.md) | A Postgres CQRS read model for search | Read side |
| [0009](0009-kafka-streams-and-postgis-for-surge.md) | Surge pricing with Kafka Streams and PostGIS zone resolution | Pricing |
| [0010](0010-advisory-lock-gated-ledger-checkpoints.md) | Advisory-lock gated ledger checkpoints and integrity audits | Money |
| [0011](0011-three-way-reconciliation-without-auto-correction.md) | Three-way reconciliation that reports and never corrects | Money |
| [0012](0012-postgis-proof-of-delivery-and-hmac-otp.md) | Proof of delivery within 50 metres with an HMAC-protected OTP | Delivery |
| [0013](0013-shared-buildkit-container-architecture.md) | One shared BuildKit Dockerfile for all applications | Packaging |

Format: `Status` (Accepted, Superseded), `Date`, `Related`, then `Context`, `Decision`, `Consequences` and
`Alternatives considered`. Operational procedures that follow from these decisions live in the [runbook](../runbook.md).
