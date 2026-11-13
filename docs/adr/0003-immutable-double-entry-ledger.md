# ADR-0003: An immutable, generic double-entry ledger

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0004](0004-temporal-saga-orchestration.md), [ADR-0010](0010-advisory-lock-gated-ledger-checkpoints.md), [ADR-0011](0011-three-way-reconciliation-without-auto-correction.md)

## Context

The platform holds customer money in escrow and distributes it to a merchant, a courier and itself when a shipment
ends, with penalties and refunds in between. Any bug in that arithmetic is a financial incident, and an error that
can be silently "fixed" with an `UPDATE` is an audit failure. We need to answer at any time where every kuruş is.

## Decision

- **Double entry.** A journal transaction consists of two or more entries; the sum of debits equals the sum of credits
  (checked in code and again in the database). Accounts have a type and a normal side. System accounts are
  `ASSET:PSP_CLEARING`, `LIABILITY:ESCROW` (per shipment), `REVENUE:PLATFORM_FEE` and `EXPENSE:LOST_COMPENSATION`;
  wallets are `LIABILITY:MERCHANT_WALLET:<id>` and `LIABILITY:COURIER_WALLET:<id>`.
- **Money is `long` kuruş in `TRY`**, `CHECK (amount_minor > 0)` and `CHECK (currency = 'TRY')`. No floating point
  anywhere near money. Splits are integer arithmetic with the remainder going to the platform.
- **Append only.** Triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on accounts, journal transactions and entries.
  Mistakes are corrected by a **reversal**: a new transaction that mirrors the original (the service verifies the mirror
  and rejects a second reversal).
- **Idempotent writes.** Every `PostTransaction` carries an idempotency key and a request hash computed over the
  canonical, order-insensitive form of its entries. The same key with the same content returns the original id
  (`replayed`), the same key with different content is `ALREADY_EXISTS`.
- **Overdraft guard.** A liability account may not be driven below zero. The check runs while holding per-account
  advisory locks, so two concurrent withdrawals cannot both pass.
- **Generic engine, domain in the workflow.** `ledger-service` knows nothing about shipments, SLAs or refunds. The
  settlement templates (`DELIVERY_SETTLEMENT`, `SLA_PENALTY_REFUND`, `RETURN_SETTLEMENT`, `LOST_SETTLEMENT`,
  `CANCEL_REFUND`) are pure functions in `workflow-service`, so the business rules are unit-testable without a database
  and the ledger stays small enough to audit.
- **Cached balances.** A balance cache is maintained by the integrity checkpoint and verified against the entries by the
  full audit ([ADR-0010](0010-advisory-lock-gated-ledger-checkpoints.md)); wallets are read as credits minus debits.
- The runtime database role cannot alter any of this: it has insert and select only, owners run migrations.

## Consequences

Positive:

- The history cannot be rewritten by the application, even by a bug. The audit trail is the table itself.
- Idempotency makes retries by Temporal activities and compensations safe by default.
- Because rules live in templates, a new outcome (a new refund rule) is a new template plus tests, not a ledger change.

Negative:

- Corrections are new rows, so the ledger only grows; archival has to be designed rather than deleting rows.
- Reading a balance from scratch means summing entries; the cache and checkpoints exist for that reason.
- Strictness has a cost in tests: fixtures that need broken data (for audit tests) must disable the triggers on a
  single session.

## Alternatives considered

- **A mutable `balances` table updated in place.** Fast and simple, but unauditable and one bug away from drift.
- **Event sourcing for the whole platform.** Too heavy; only the ledger needs append-only semantics.
- **Putting settlement rules inside the ledger service.** Couples a generic engine to shipment concepts.
