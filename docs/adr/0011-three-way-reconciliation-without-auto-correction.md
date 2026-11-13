# ADR-0011: Three-way reconciliation that reports and never corrects

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0003](0003-immutable-double-entry-ledger.md), [ADR-0010](0010-advisory-lock-gated-ledger-checkpoints.md)

## Context

Three records describe the same money and can drift apart: what the PSP says happened to each payment, what
`payment-service` recorded (intents, transactions, captured and refunded amounts), and what the ledger recorded
(the clearing account per shipment). Webhooks get lost, calls time out with unknown results, workflows crash between a
capture and a ledger posting, humans change things at the PSP. Automatically "fixing" a difference is dangerous: you
cannot tell which side is wrong, and an automatic correction of a financial record is itself an event that needs review.

## Decision

- **Reconcile along both seams, report only.** `payment-service` runs a reconciliation every 30 minutes (and on demand for
  `OPS`) over a time window. It never changes a payment, an intent or a transaction, and only issues read calls to the PSP.
  Findings are immutable rows; a human decides what to do ([runbook](../runbook.md#4-psp-reconciliation-findings)).
- **Leg 1, PSP versus platform.** The PSP's report of payments updated in the window is compared with the intents updated in the
  same window by `psp_payment_id`: `UNKNOWN_AT_PLATFORM`, `MISSING_AT_PSP`, `STATUS_MISMATCH` (with a table of the PSP
  states each platform state may legitimately map to), `CAPTURED_AMOUNT_MISMATCH`, `REFUNDED_AMOUNT_MISMATCH`.
  Intents missing from the report are asked about one by one before being reported, because "not in the report" is not "not at
  the PSP".
- **Leg 2, platform versus ledger.** For each shipment, `captured - refunded` over all its intents (failed and expired
  included) must equal the net debit of the ledger's `ASSET:PSP_CLEARING` for that shipment (`GetClearingBalances`, a
  read-only gRPC): otherwise `CLEARING_MISMATCH`. If the ledger cannot be reached the run records a single
  `LEDGER_UNAVAILABLE` warning and still performs leg 1.
- **Leg 3, ledger versus itself** is the integrity audit ([ADR-0010](0010-advisory-lock-gated-ledger-checkpoints.md)).
- **Grace windows avoid false alarms.** A pair is skipped if either side changed within the last ten minutes (webhooks and
  workflows are asynchronous), and runs overlap by an hour so late changes are covered by the next run. A shipment with any
  intent updated within the grace period is skipped in leg 2.
- **Protect against a broken reference.** If the PSP report is empty but our recent intents exist and a sample of three is also
  unknown at the PSP (the simulator restarted and lost its memory), the run records one `PSP_REPORT_UNAVAILABLE`
  (`EMPTY_REPORT`) instead of hundreds of `MISSING_AT_PSP`; more than 50 confirmed misses collapse into `MASS_MISSING`;
  an unreachable PSP fails the run (`UNREACHABLE`) but stuck-transaction detection still runs.
- **Stuck work is findings too.** `STUCK_TRANSACTION` flags PSP operations that stayed pending, in progress or unknown for more
  than fifteen minutes.
- **Alarm.** A run with findings writes a `ReconciliationDiscrepancyDetected` event to the outbox in the same transaction (types
  and counts, no identifiers) and logs `RECONCILIATION_DISCREPANCY` at `ERROR`. Findings are capped at 1000 per run
  (`TRUNCATED`).

## Consequences

Positive:

- A discrepancy cannot be made worse or hidden by the system, and every difference is documented evidence with the numbers
  needed to judge it.
- Both seams are covered without a distributed transaction between the PSP, payments and the ledger.
- The safeguards turn the typical false-alarm storms (empty PSP, slow workflow) into one meaningful finding.

Negative:

- Resolution is manual, which means an on-call procedure ([runbook](../runbook.md)) and metrics/alerts (reconciliation
  discrepancies, staleness) to make sure someone looks.
- The ledger leg only sees shipments whose intents changed in the window; a ledger change long after the payment changed is
  caught by a later run only through the overlap or by the integrity audit.
- The PSP report is held in memory per run; a very large window is bounded by a maximum manual window (seven days).
- A slow workflow that posts the ledger entry later than the grace period can produce a transient `CLEARING_MISMATCH`.

## Alternatives considered

- **Auto-correct with compensating entries.** Rejected: the platform cannot know which record is right.
- **Real-time two-phase commit with the PSP.** Not available from a PSP and fragile.
- **Only reconcile with the PSP.** Leaves the payment-to-ledger gap (the most common failure: a crash between capture and posting).
