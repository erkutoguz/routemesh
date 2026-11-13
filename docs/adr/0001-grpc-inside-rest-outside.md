# ADR-0001: gRPC between services, REST (and SSE) at the edge

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0002](0002-transactional-outbox-and-debezium-cdc.md), [ADR-0005](0005-zero-trust-jwt-and-ownership-isolation.md)

## Context

Twelve services need to call each other for commands (create a shipment, capture a payment, post a ledger
transaction) and to ask questions (what does this quote cost, who is near the depot). The same platform serves
browsers and mobile apps that expect plain HTTP, JSON error documents and a way to stream a courier's position to a
merchant. Two audiences, two sets of needs:

- Between services we want a strict, versioned contract, generated clients, deadlines on every call, and streaming
  (a courier app sends a continuous position stream).
- At the edge we want something every client already speaks, that works through proxies, and whose errors a human can read.

## Decision

- **Inside the platform every command and query is a gRPC call** defined in `contracts` (protobuf, packages versioned as
  `routemesh.<area>.v1`). Clients are Spring gRPC channels with an explicit default deadline per channel.
- **Outside the platform everything is REST with RFC 9457 `problem+json` errors**, plus Server-Sent Events where the server
  pushes (live tracking). Browsers and mobile apps only ever talk to the `api-gateway`.
- **Kafka carries facts, never commands** ([ADR-0002](0002-transactional-outbox-and-debezium-cdc.md)). A service that
  wants something done calls the owner over gRPC; a service that wants to know what happened consumes an event.
- gRPC is reserved for the `WORKFLOW` service role (plus the courier position stream), so the REST surface and the gRPC
  surface of a service have different callers and different authorization rules ([ADR-0005](0005-zero-trust-jwt-and-ownership-isolation.md)).
- Because gRPC calls carry no human actor, every transition arriving over gRPC is a `SYSTEM` transition. The `actor`
  field was removed from `shipment.proto` for that reason; warehouse, courier, merchant and ops actions can only arrive
  through REST with their own token.

## Consequences

Positive:

- One schema is the source of truth for both sides, so a breaking change fails the build instead of production.
- Deadlines, streaming and status codes are part of the protocol; the workflow layer maps `INVALID_ARGUMENT`, `NOT_FOUND`,
  `FAILED_PRECONDITION` and friends to "do not retry" and everything else to "retry".
- The edge stays boring: `curl`, a browser or a load balancer all work. SSE needs no extra protocol and the MVC
  gateway forwarded it without buffering.

Negative:

- Two API styles to maintain and to secure. Each service therefore has two separately tested authorization surfaces.
- Server reflection is denied by the default security rule, so tooling needs the `.proto` files (`grpcurl -proto`).
- gRPC through generic HTTP infrastructure needs HTTP/2 end to end; internal traffic stays on the private network.

## Alternatives considered

- **REST everywhere.** Simpler tooling, but no generated clients, no streaming for telemetry and a looser contract.
- **GraphQL at the edge.** Not needed: the clients have a handful of fixed screens and the read side is already a CQRS model.
- **Commands over Kafka.** Would hide failures (no synchronous answer) and blur "what happened" with "please do this".
