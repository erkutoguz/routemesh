# ADR-0007: Keyset (cursor) pagination for every list endpoint

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0008](0008-postgres-cqrs-search-read-model.md)

## Context

The platform has several append-heavy lists: a merchant's shipments, a wallet's transactions, notifications, ledger
integrity runs and findings, reconciliation runs and findings, the PSP simulator's report. They grow constantly and are
read while they grow. `OFFSET n` pagination re-scans `n` rows per page and shifts under inserts, producing duplicates and
skips; clients that page through a live list see inconsistent data.

## Decision

- **Every list uses keyset pagination on a total order that includes a unique tiebreaker**: `(created_at DESC, id DESC)`
  for shipments and notifications, `entry_id DESC` for wallet transactions, the run start time plus id for runs, the finding id for
  findings. Each order is backed by an index whose key columns match it.
- **The cursor is opaque** (base64url of the last row's key, for example `<epoch microseconds>:<id>`), a `nextCursor` is returned
  only when more rows exist (the query fetches `limit + 1`), and the next request returns strictly older rows. A malformed
  cursor, an unknown filter value or an out-of-range `limit` is `400 problem+json`.
- **Limits are bounded** (default 20 or 50, maximum 100).
- **Timestamps keep microsecond precision in the cursor.** Rounding to milliseconds would let rows with the same
  millisecond fall between pages.
- **Filters compose with the cursor** (status, courier, date range with `from` inclusive and `to` exclusive) and ownership
  is part of the query, so a cursor taken from someone else's list cannot reveal anything.

## Consequences

Positive:

- Constant cost per page regardless of depth, stable results while data is inserted, and no duplicates or skips.
- The same convention everywhere keeps clients and tests uniform (walk the whole list, assert exactly the database count).

Negative:

- No "jump to page N" and no total count; UIs must be infinite-scroll or next/previous.
- Changing a sort order means a new index and a new cursor shape.
- A row whose sort key changes after it has been paged past (a read-model skeleton row completed later) can appear
  out of order; this is rare and accepted.

## Alternatives considered

- **Offset/limit.** Simple, but degrades with depth and is unstable under writes.
- **Server-side cursors or snapshots.** Hold state per client and do not suit a stateless HTTP API.
