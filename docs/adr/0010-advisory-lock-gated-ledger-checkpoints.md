# ADR-0010: Advisory-lock gated ledger checkpoints and integrity audits

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0003](0003-immutable-double-entry-ledger.md), [ADR-0011](0011-three-way-reconciliation-without-auto-correction.md)

## Context

We want to verify the ledger continuously (every transaction balances, no liability is negative, nothing old was rewritten)
without re-reading all history each time, and to be able to detect tampering after the fact. The natural design is
a checkpoint: "everything up to entry id N was verified, here is the running total and a hash". There is a trap:
`ledger_entries.id` is an `IDENTITY` column. A transaction can take id 100, another can take id 101 and commit first, and
a checkpoint that reads "up to the highest id" and records 101 would skip entry 100 forever once it commits.

## Decision

- **A gate key serializes checkpoints against writers, not writers against each other.** Every `PostTransaction` takes
  `pg_advisory_xact_lock_shared(hashtextextended('ledger:entries-gate', 0))` (together with the existing per-account locks).
  Writers share the lock, so they do not block one another. The incremental audit takes the same key **exclusively**
  (`pg_advisory_xact_lock`) inside its transaction: it waits for in-flight writers to finish, new writers wait for it, and
  it then sees a gap-free prefix of ids. A configurable `lock_timeout` stops the audit from waiting forever.
- **Checkpoints are chunked and never split a transaction.** A run scans from the last checkpoint to
  `min(max id, last + chunk size)` and extends the upper bound so every transaction in range is complete. Each chunk is its own
  database transaction (the exclusive lock is held per chunk, not for the whole run).
- **A checkpoint stores** the last entry id, cumulative entry count, cumulative debit and credit totals and a chain hash:
  `sha256(previous hash, entry id, transaction id, account id, direction, amount ...)` starting from `sha256("ledger-genesis")`.
  Checkpoints, findings and the balance cache rows are written in the same transaction as the scan.
- **A finding does not stop the checkpoint.** The checkpoint advances even when the chunk had findings (otherwise the same
  finding would be re-reported forever); findings are immutable evidence.
- **Two modes.** `INCREMENTAL` (every 15 minutes) scans only what is new. `FULL` (daily, or on demand) is lock-free: it
  runs in a `REPEATABLE READ` read-only snapshot, recomputes each checkpoint segment starting from the *stored* previous
  hash (so one corrupted segment does not cascade into every later one), recomputes all balances from entries and compares
  them with the cache. It only reports.
- **Finding types:** `UNBALANCED_TRANSACTION`, `TOTALS_MISMATCH`, `NEGATIVE_BALANCE`, `REVERSAL_MISMATCH`,
  `HISTORY_TAMPERED`, `CACHE_DRIFT`, `TRUNCATED` (more than 1000 findings in one run).
- **One run per mode at a time**, enforced by a partial unique index on `status = 'RUNNING'`; a sweeper marks runs stuck for
  30 minutes as `FAILED`. A violation writes an alarm event to the outbox (types and counts only) and an `ERROR` log line.

## Consequences

Positive:

- No entry can be skipped, however commits interleave, and the audit cost is proportional to new data.
- Tampering with an old entry changes a recomputed hash and is caught by the daily full audit even if the totals still balance.
- Writers are not serialized against each other: the shared lock costs almost nothing.

Negative:

- While a checkpoint chunk runs, new postings wait; chunk size and `lock_timeout` bound the pause.
- Advisory locks are cooperative: any code path that writes entries without taking the shared gate would break the guarantee, so the
  gate acquisition lives in the single posting routine.
- The full audit reads the whole ledger and holds a long snapshot; its duration and memory were not measured at scale.

## Alternatives considered

- **Track by timestamp or by "last id".** Skips late commits (the exact bug above).
- **Make writers wait for each other** (a single exclusive lock). Correct, but serializes all ledger writes.
- **A single `SERIALIZABLE` scan.** Aborts under write load instead of waiting briefly.
