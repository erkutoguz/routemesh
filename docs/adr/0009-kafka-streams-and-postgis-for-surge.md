# ADR-0009: Surge pricing with Kafka Streams and PostGIS zone resolution

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0002](0002-transactional-outbox-and-debezium-cdc.md)

## Context

When many shipments appear in an area and few couriers are idle there, the price should rise; when supply recovers, it
should fall back without oscillating. The signals already exist as events (courier status, courier positions, new
shipments). The price must be computed per zone, kept out of the quote path's latency, and fail safe: if the signal
disappears the customer must pay the normal price, never a stale high one.

## Decision

- **Kafka Streams in a dedicated `surge-service`.** Three inputs: `fleet.events` (`CourierStatusChanged`),
  `courier.locations` (positions) and `shipment.events` (`ShipmentCreated`, which carries the delivery coordinates). Courier
  status and position are joined in a KTable so only `IDLE` couriers count as supply; each signal is resolved to a zone and
  repartitioned by zone so one processor instance owns a zone's state.
- **Zone resolution has a single source of truth: `pricing-service`.** `LocateZone` uses the same PostGIS rule as a quote
  (`ST_Covers`, active zones only, highest priority then smallest area), so a point can never be in one zone for pricing and
  another for surge. `ST_Covers` rather than `ST_Contains` so points on a boundary count and match the quote. `surge-service`
  snaps coordinates to a 0.005 degree grid, asks about the cell centre and caches the answer (including "outside any zone")
  for an hour; a failed lookup is not cached and the signal is dropped.
- **Per-zone state** lives in a persistent Kafka Streams store: minute buckets for supply (5 minute window) and demand
  (15 minute window). A punctuator recomputes every 30 seconds.
- **The formula is integer arithmetic.** If demand is under 3 the multiplier is 1.00. Otherwise
  `target = clamp(10000 + floor((demand / max(supply, 1) - 1) * 5000), 10000, 25000)` in basis points.
- **Damping prevents flapping.** An increase of at least 500 bps jumps to the target; a decrease of at least 500 bps steps down
  by at most 1000 bps per tick; a smaller change is ignored; reaching the floor or the ceiling is exact.
- **Output is a Redis key** `pricing:surge:<zone>` holding the bps with a 10 minute TTL, rewritten every tick; a multiplier of
  exactly 1.00 deletes the key. `pricing-service` reads it when it creates a quote, clamps it to its configured maximum and
  treats anything invalid as 1.00. If `surge-service` stops, the keys expire and prices return to normal.

## Consequences

Positive:

- Event-driven and decoupled: neither the shipment path nor the quote path waits for surge, and quotes are cheap (one Redis read).
- The stateful windowing logic is testable with `TopologyTestDriver` (formula examples, windows expiring, damping, bounds).
- Fail-safe by TTL.

Negative:

- A coordinate near a zone border can be off by up to roughly the grid size (about 275 m) in the zone decision.
- Kafka Streams adds operational weight (internal topics, state stores, rebalances) for one multiplier.
- Surge is eventually consistent with what the customer sees: a quote already created keeps its price for its ten minutes.
- Only one instance was exercised end to end; multi-instance behaviour rests on repartitioning by zone.

## Alternatives considered

- **Compute demand and supply on every quote.** Couples quote latency to cross-service reads.
- **H3 hexagons.** Unnecessary for a small number of named service zones and would add a dependency to the location path.
- **A scheduled SQL job.** Would need the signals in a database first and loses the streaming windows.
