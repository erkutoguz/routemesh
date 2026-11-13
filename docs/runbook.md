# Operations runbook

First-response procedures for the situations the platform is designed to surface. Commands were exercised against the
Docker Compose stack described in the [README](../README.md#quickstart); container names are `routemesh-<service>`.
Where a procedure is a judgement call rather than a mechanical step, it says so.

Contents: [0. Before you start](#0-before-you-start) ·
[1. Keycloak realm re-import](#1-keycloak-realm-re-import) · [2. Dead-letter topics](#2-dead-letter-topics) ·
[3. Ledger integrity](#3-ledger-integrity) · [4. PSP reconciliation findings](#4-psp-reconciliation-findings) ·
[5. Other first responses](#5-other-first-responses) · [6. Alert map](#6-alert-map)

## 0. Before you start

Principles that apply to everything below:

- **The system reports; people decide.** The ledger audit and the reconciliation never change a payment or a ledger row
  ([ADR-0010](adr/0010-advisory-lock-gated-ledger-checkpoints.md), [ADR-0011](adr/0011-three-way-reconciliation-without-auto-correction.md)).
  Do not "fix" money by editing rows. The append-only triggers will stop an `UPDATE`; do not disable them. Corrections are new,
  reversing ledger transactions or actions at the PSP, each with a ticket reference in its idempotency key.
- **Findings are immutable evidence.** There is no "resolved" flag. You resolve the cause, run the audit again, and expect a clean run;
  old findings stay.
- **Preserve before you touch.** Copy the finding JSON and the relevant run ids into the incident record first.

Shell setup used in the examples (development credentials; use your real operator account elsewhere):

```bash
# An OPS token (valid for five minutes; request a new one when calls start returning 401)
OPS=$(curl -s http://localhost:8180/realms/routemesh/protocol/openid-connect/token \
  -d grant_type=password -d client_id=routemesh-web -d username=ops -d password=ops-dev | jq -r .access_token)
GW=http://localhost:8080
# a Kafka CLI inside the broker container (on Git Bash for Windows prefix commands with MSYS_NO_PATHCONV=1)
kafka() { docker exec -i routemesh-kafka /opt/kafka/bin/"$@"; }
```

## 1. Keycloak realm re-import

**Why this is needed.** Keycloak starts with `--import-realm`, which imports `docker/keycloak/routemesh-realm.json`
**only if the realm does not exist yet**. Editing the file, rebuilding the image or pulling a new version does not change an
existing database. Typical symptoms after the realm file gained something new (a client, a role, a secret):

- `invalid_client` ("Invalid client or Invalid client credentials") when a service requests a token, in a service's log
  as a failed token fetch or as Kafka Streams / gRPC calls that never authenticate;
- a token without the expected role, so gRPC answers `PERMISSION_DENIED`.

Pick the least destructive option that fits.

### Option A: add or repair one client without touching anything else (preferred)

Use the admin API's partial import with `SKIP`, so existing resources are left alone. A service-account client needs **two**
entries: the client and its service-account user (importing the client alone creates it without the user and the token request
fails with "The associated service account for the client does not exist").

```bash
ADMIN=$(curl -s http://localhost:8180/realms/master/protocol/openid-connect/token \
  -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin | jq -r .access_token)

CLIENT=surge-service
jq --arg c "$CLIENT" '{ifResourceExists: "SKIP",
      clients: [.clients[] | select(.clientId == $c)],
      users:   [.users[]   | select(.serviceAccountClientId == $c)]}' docker/keycloak/routemesh-realm.json \
  | curl -s -X POST http://localhost:8180/admin/realms/routemesh/partialImport \
      -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d @- | jq '{added, skipped}'
```

Expected `added: 2` (or `skipped` when it was already there). Verify with a token request and check the role claim:

```bash
curl -s http://localhost:8180/realms/routemesh/protocol/openid-connect/token -d grant_type=client_credentials \
  -d client_id=$CLIENT -d client_secret=$CLIENT-dev-secret | jq -r .access_token | cut -d. -f2 | tr '_-' '/+' \
  | awk '{while (length($0) % 4) $0 = $0 "="; print}' | base64 -d | jq .realm_access      # {"roles":["WORKFLOW", ...]}
```

### Option B: delete and re-import the whole realm

Needed when the realm file changed in ways a partial import cannot express (role definitions, mappers, realm settings). This
**deletes everything in the realm**: users, credentials and sessions are replaced by what the file contains.

```bash
curl -s -X DELETE -H "Authorization: Bearer $ADMIN" http://localhost:8180/admin/realms/routemesh   # 204
docker compose -f docker/docker-compose.yml restart keycloak
until [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8180/realms/routemesh/.well-known/openid-configuration)" = 200 ]; do sleep 3; done
```

The realm is imported again on the restart. The realm is created with **new signing keys**; the services fetch the JWKS again on an
unknown key id, so they accept new tokens without a restart (verified), but tokens issued before the delete stop
working and long-lived clients (SSE streams, scripts) must obtain new ones.

### Option C: development reset

On a throwaway development stack, `docker compose down -v` removes every volume and the next `up` imports the realm
into a fresh database. A fresh `postgres` volume also re-runs `docker/postgres/init/*.sql`, which is what creates the
`keycloak` database in the first place.

### Notes

- The realm file ships **development** secrets (`<service>-dev-secret`) and the password grant for the public client. Outside
  development, supply secrets through the services' `*_CLIENT_SECRET` environment variables and rotate them in the admin
  console; do not import the development realm.
- Keycloak access tokens live five minutes in this realm.

## 2. Dead-letter topics

**Where they are.** Consumers with side effects publish records they could not process to
`<source topic>.<consumer>.dlt` (14 days retention): `shipment.events.notification-service.dlt`,
`fleet.events.notification-service.dlt`, `location.events.notification-service.dlt` and
`shipment.events.search-service.dlt`, `shipment.events.workflow-service.dlt` (the dispatch, settlement and courier-release relays;
tell them apart by `kafka_dlt-consumer-group`) and `shipment.events.location-service.dlt` (proximity watch).
Background: [ADR-0006](adr/0006-inbox-and-dead-letter-topics.md).

**Signals.** Alert `DeadLetterGrowth` (metrics `routemesh_{notification,workflow,location}_dlt_messages_total{topic}`; `search-service` has no
counter, so watch its topic directly), and consumer errors in the service log.

### Inspect

```bash
# how many records landed, per partition (the sum of the end offsets)
kafka kafka-get-offsets.sh --bootstrap-server kafka:19092 --topic shipment.events.notification-service.dlt --time latest

# read them with partition, offset, key and the headers that carry the failure
kafka kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic shipment.events.notification-service.dlt \
  --from-beginning --timeout-ms 8000 \
  --formatter-property print.headers=true --formatter-property print.key=true \
  --formatter-property print.partition=true --formatter-property print.offset=true
```

Every record keeps its original payload and adds these headers: `kafka_dlt-exception-fqcn`,
`kafka_dlt-exception-cause-fqcn`, `kafka_dlt-exception-message`, `kafka_dlt-exception-stacktrace`, and where it came from:
`kafka_dlt-original-topic`, `-partition`, `-offset`, `-timestamp`, `-timestamp-type`, `-consumer-group`. A poison record
sent to `shipment.events` shows `...StreamReadException: Unexpected character` as the cause.

### Triage

| Cause (header) | What it means | Action |
|---|---|---|
| `StreamReadException` / `JacksonException` | The payload is not valid JSON for this contract. | A producer bug or a manual test record. Not replayable. Find the producer (the original topic, partition and offset identify the record), fix it, and leave the record. |
| `IllegalArgumentException` (bad UUID, missing field), `DateTimeException` | The event violates the contract. | Same as above: producer or contract bug. |
| `DataIntegrityViolationException` (search) | A `CHECK` rejected it, for example a creation event with a zero amount. | Inspect the event; the read model is correct to refuse it. Fix the producer. |
| gRPC `NOT_FOUND`, `INVALID_ARGUMENT`, `FAILED_PRECONDITION`, `PERMISSION_DENIED` (notification, via `NonRetryableEventException`) | The event refers to a shipment `shipment-service` does not know, or in a state where the side effect no longer applies (for example an OTP for a finished shipment), or the service account lost its role. | If `PERMISSION_DENIED`: check the service account ([section 1](#1-keycloak-realm-re-import)), then replay. Otherwise it is usually a stale or foreign event: record and discard. |
| `UNAVAILABLE` / timeout / database errors after five attempts | A dependency was down longer than the retry window (1 s up to 16 s, five attempts). | Wait until the dependency is healthy, then replay. |

### Replay (manual)

There is no replay tool. Replaying means publishing the payload to the **source topic** again with the same key. This is safe
because every consumer is idempotent: records that already succeeded are skipped by the inbox (search and notification), the
workflow relays act by version or workflow id, and the proximity watch is version-guarded. Only the consumer that failed
actually performs work.

```bash
# 1. dump key and value (the separator is chosen so it cannot appear in a UUID key)
kafka kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic shipment.events.notification-service.dlt \
  --from-beginning --timeout-ms 8000 --formatter-property print.key=true --formatter-property 'key.separator==>' \
  > dlt-dump.txt        # review it, keep only the records you want replayed

# 2. republish to the source topic (use the kafka_dlt-original-topic header; the key is the aggregate id)
kafka kafka-console-producer.sh --bootstrap-server kafka:19092 --topic shipment.events \
  --reader-property parse.key=true --reader-property 'key.separator==>' < dlt-replay.txt
```

If a record prints with key `null`, omit the two `parse.key` properties. Watch the consumer's log and the dead-letter offsets: they
must not grow again. If the same record fails again, the cause was not fixed.

### Limits to be aware of

- Replaying does not remove the original dead-letter record; offsets only move forward. Note replays in the incident record.
- Records are only retained for 14 days in the dead-letter topics (7 days for the source topics).
- The `workflow-service` relays and the `location-service` proximity watch retry a transient failure with exponential back-off
  (1 s up to 60 s, about ten minutes in total) and then write the record to their dead-letter topic; a malformed or
  permanently invalid record goes there at once. While a record is retried its partition waits. A dead-lettered settlement or
  courier release did not happen: replay it after fixing the cause, or find the shipment in the Temporal UI
  (`settlement-<shipmentId>` workflow) or release the courier by hand ([section 5](#5-other-first-responses)).

## 3. Ledger integrity

The ledger audit is described in [ADR-0010](adr/0010-advisory-lock-gated-ledger-checkpoints.md): an incremental run every 15
minutes and a full audit daily at 03:00. All endpoints are `OPS` only through the gateway under `/api/v1/ops/ledger`.

```bash
curl -s -H "Authorization: Bearer $OPS" $GW/api/v1/ops/ledger/integrity | jq '{status, latestCheckpoint, negativeBalances}'   # summary
curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/ops/ledger/integrity-runs?limit=5" | jq '.items[] | {mode, status, findingsCount}'
curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/ops/ledger/findings?runId=<run id>&type=<TYPE>" | jq '.items'
curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/ops/ledger/checkpoints?limit=5" | jq .
# start a run: INCREMENTAL answers 200 when it finishes, FULL answers 202 and runs in the background (409 if one is running)
curl -s -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d '{"mode":"FULL"}' $GW/api/v1/ops/ledger/integrity-runs | jq '{id, status}'
```

Summary `status`: `HEALTHY` (last runs clean), `VIOLATIONS` (the last run found something or the cache holds a negative liability),
`DEGRADED` (the last run `FAILED`), `UNKNOWN` (no run yet).

### Alert `LedgerCheckpointLag` (the audit is behind)

`routemesh_ledger_integrity_checkpoint_lag_entries` is `max(ledger_entries.id) - last checkpoint id`; it alerts above 50,000 for
30 minutes. The ledger itself is fine; the audit is not keeping up or not running.

1. `GET /integrity-runs?limit=5`: are incremental runs happening every 15 minutes and `OK`? Several `FAILED` runs mean look at
   the `error` column (most often a `lock_timeout`: writers held the gate for too long) and the ledger log.
2. A run stuck in `RUNNING` is failed automatically after 30 minutes; a second `RUNNING` of the same mode is refused (`409`).
3. If the scheduler was disabled (`routemesh.ledger.integrity.scheduler-enabled=false`), trigger a manual incremental run. It
   processes at most one chunk per transaction (100,000 entries by default) and loops until it has caught up.

### Alert `LedgerIntegrityViolation` / summary `VIOLATIONS`

Stop and read the finding before doing anything. `GET /findings?runId=...` returns `type`, `severity`, the account and shipment
or transaction involved, `expectedMinor` (recomputed from entries) versus `actualMinor`, and a `detail` object.

| Finding | Meaning | First checks | Resolution |
|---|---|---|---|
| `UNBALANCED_TRANSACTION`, `TOTALS_MISMATCH` | A transaction's debits differ from its credits, or a chunk's totals do. The service and the database both refuse this, so it means a write that bypassed them (a direct SQL change, a disabled trigger, a restored backup). | Which transaction (`transactionId`); who connected as the database owner around its `createdAt`; the outbox row for it. | Security incident first. Do not touch the entries. A correcting transaction does not fix an unbalanced one; restore from a verified backup or involve the data owner, then run a full audit. |
| `NEGATIVE_BALANCE` | A liability (a wallet, or one shipment's escrow) has a net debit. | Is it a wallet or an escrow? List the account's entries; compare with the shipment's settlement transactions and payment refunds. | The cause is usually a duplicated or mis-sized settlement, refund or compensation. Post a reversing transaction (`PostTransaction` with `reversal_of` set, which the service verifies as an exact mirror) or a corrective one, with a ticket in the idempotency key. |
| `REVERSAL_MISMATCH` | A reversal does not mirror its original (entries or `shipment_id`). | Both transaction ids. | As above: the original stays, add a corrective transaction. |
| `HISTORY_TAMPERED` | A checkpoint's stored hash, count or totals do not match a recomputation from the entries. **Entries already audited were changed or deleted.** `detail` has `storedHash`, `recomputedHash`, `fromEntryId`, `toEntryId`. | The entry range; backups and database audit logs; whether a migration or restore touched it. | Treat as a security incident. Preserve the finding, the checkpoint rows and a database snapshot. Never re-checkpoint to make it go away. |
| `CACHE_DRIFT` | The balance cache differs from the balance recomputed from entries. **The entries are the truth.** The cache is derived data used only by the audit. | Is the entry range intact (no `HISTORY_TAMPERED`)? If yes, only the cache is wrong. | Rebuild the cache (below), then run a full audit and expect `OK`. |
| `TRUNCATED` | More than 1000 findings in one run; the rest were dropped from the report. | The first 1000 findings usually show the pattern. | Fix the cause and run again. |

#### Rebuild the balance cache (for `CACHE_DRIFT` only)

Run as the database owner. The transaction takes the same advisory gate the audit uses (exclusive), so no posting runs while it rebuilds, and
it recomputes only up to the last checkpoint, which is what the cache represents. It is quick; writers wait a moment.

```sql
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('ledger:entries-gate', 0));
DELETE FROM ledger_balance_cache;
INSERT INTO ledger_balance_cache (account_id, scope_shipment_id, net_debit_minor)
SELECT e.account_id,
       CASE WHEN a.code = 'LIABILITY:ESCROW'
            THEN COALESCE(t.shipment_id, '00000000-0000-0000-0000-000000000000'::uuid)
            ELSE '00000000-0000-0000-0000-000000000000'::uuid END,
       SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END)
FROM ledger_entries e
JOIN accounts a ON a.id = e.account_id
JOIN journal_transactions t ON t.id = e.transaction_id
WHERE e.id <= (SELECT COALESCE(max(last_entry_id), 0) FROM ledger_checkpoints)
GROUP BY 1, 2
HAVING SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END) <> 0;
COMMIT;
```

```bash
docker exec -i routemesh-postgres psql -U postgres -d ledger < rebuild-cache.sql
curl -s -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d '{"mode":"FULL"}' $GW/api/v1/ops/ledger/integrity-runs
```

Verified end to end: a cache row changed by hand produced one `CACHE_DRIFT` finding (`expectedMinor` -609, `actualMinor` -604 on
`REVENUE:PLATFORM_FEE`), the summary turned `VIOLATIONS`; after the rebuild a full audit finished `OK` with no findings and the summary
returned to `HEALTHY`.

## 4. PSP reconciliation findings

The reconciliation is described in [ADR-0011](adr/0011-three-way-reconciliation-without-auto-correction.md): every 30 minutes, and on
demand, over a window that overlaps the last completed one by an hour. Both seams are checked: PSP versus platform, and platform
versus ledger clearing. Nothing is changed. Endpoints (`OPS`, through the gateway) under `/api/v1/ops/payments`:

```bash
curl -s -H "Authorization: Bearer $OPS" $GW/api/v1/ops/payments/reconciliation | jq '{status, latestRun}'          # summary
curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/ops/payments/reconciliation-runs?status=DISCREPANCIES&limit=5" | jq .
curl -s -H "Authorization: Bearer $OPS" $GW/api/v1/ops/payments/reconciliation-runs/<run id> | jq '{run, findingsByType, findingsBySeverity}'
curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/ops/payments/reconciliation-findings?runId=<run id>&severity=CRITICAL" | jq '.items'
# start a run now (optional window, at most seven days; 202, or 409 when one is running)
curl -s -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d '{}' $GW/api/v1/ops/payments/reconciliation-runs
```

Summary `status`: `OK` (last finished run clean), `DISCREPANCIES`, `DEGRADED` (the last run `FAILED`), `UNKNOWN`. A run with findings also
writes a `ReconciliationDiscrepancyDetected` event (types and counts only) and an `ERROR` log line `RECONCILIATION_DISCREPANCY`.
Alerts: `ReconciliationDiscrepancies` (a run in the last hour found something) and `ReconciliationStale` (no successful run for two hours).

### The finding triage

A finding carries the `type`, a `severity` (`CRITICAL` or `WARNING`), the intent, shipment, PSP payment and transaction ids that apply,
the local and PSP status and amounts, and a `detail` object.

| Finding | What it says | Likely causes | First checks and action |
|---|---|---|---|
| `STATUS_MISMATCH` | Our status is not one the PSP's status may legitimately map to. | A lost or rejected webhook; a late authorization or capture after our payment expired and its void or refund has not completed; a manual action at the PSP. | Compare `localStatus` with `pspStatus`. Look at the intent's transactions and webhook events in the payment database. For a late payment on an expired intent, wait for the automatic void/refund and rerun. Otherwise correct at the PSP or re-deliver the webhook; the payment service applies state only through its own transitions. |
| `CAPTURED_AMOUNT_MISMATCH`, `REFUNDED_AMOUNT_MISMATCH` | The PSP's captured or refunded total differs from ours (`detail.differenceMinor` is the PSP's amount minus ours: -100 means the PSP captured 100 less). A lost capture webhook produces both. | Webhook lost or ignored; partial capture/refund done at the PSP; a PSP-side adjustment. | Establish which side is right from the PSP's records. If the PSP is right, the platform's transaction did not complete: check `payment_transactions` for the intent. If the platform is right, raise it with the PSP. Also check the ledger leg for the same shipment. |
| `MISSING_AT_PSP` | We have an intent past the grace period that the PSP report does not list **and** a direct lookup says the PSP does not know it. | The PSP lost data; the PSP id was never persisted correctly; wrong environment. | Confirm with the PSP by id. Nothing in our system creates the missing payment: treat as a financial incident if money was reported captured. |
| `UNKNOWN_AT_PLATFORM` | The PSP has a payment we have no intent for. | A payment created by something other than this platform (or in another environment), or an intent that was never saved (a crash between the PSP call and our commit). | Look it up at the PSP by reference; if it belongs to us, an orphan: refund or void it at the PSP. |
| `STUCK_TRANSACTION` (`WARNING`) | A PSP operation (capture, void, refund) has been `PENDING`, `IN_PROGRESS` or `UNKNOWN` for more than 15 minutes. `detail` has `type`, `status`, `amountMinor`, `attempts`, `lastError`, `openSince`. | The PSP was unreachable or timed out with an unknown result; the retrier ran out of attempts (default 10, 30 s apart). | Read `lastError`. The operation's idempotency key makes a retry safe, so once the PSP is reachable the retrier completes it; if attempts are exhausted, check at the PSP whether it applied. There is no in-product "retry" action. Do not create a second operation by hand. |
| `PSP_REPORT_UNAVAILABLE` (`CRITICAL`) | The reference data cannot be trusted. `detail.reason`: `EMPTY_REPORT` (the PSP lists nothing for the window, and a sample of our intents is unknown to it too: the PSP lost data or we are pointed at the wrong one), `MASS_MISSING` (more than 50 intents appear missing), `UNREACHABLE` (the run `FAILED`; stuck-transaction checks still ran). | A PSP outage, a PSP reset (the simulator does this when restarted), a wrong base URL or credentials. | Fix connectivity or the PSP target, then rerun. Individual `MISSING_AT_PSP` findings are deliberately not produced in these cases. |
| `CLEARING_MISMATCH` (`CRITICAL`) | The ledger's `ASSET:PSP_CLEARING` net debit for a shipment is not `captured - refunded` of its intents. | A payment captured or refunded without the matching ledger posting (workflow failed between the two) or the other way around; a duplicated posting; a manual ledger correction. | See "Reading a `CLEARING_MISMATCH`" below. |
| `LEDGER_UNAVAILABLE` (`WARNING`) | The ledger leg could not run; the PSP leg did. `detail.reason`: `UNREACHABLE`, `NOT_AUTHORIZED` (the service account lost the `WORKFLOW` role, see [section 1](#1-keycloak-realm-re-import)), `REJECTED_<code>`, `BAD_RESPONSE_OR_TOKEN`. | Ledger down, deadline exceeded, token problem. | Restore the ledger or the account, rerun. Meanwhile the ledger leg is blind. |
| `TRUNCATED` (`WARNING`) | More findings or lookups than the per-run limits (1000 findings, 1000 individual lookups). | A systemic problem. | Fix the pattern behind the first findings and rerun. |

### Reading a `CLEARING_MISMATCH`

`detail` has `expectedNetMinor` (the sum over **all** the shipment's intents of captured minus refunded), `ledgerClearingNetMinor` (the
net debit of `ASSET:PSP_CLEARING` for that shipment) and `differenceMinor` = ledger minus expected, plus the `intentIds`. A real example from a
shipment whose ledger got an extra posting of 50 kuruş:

```json
{ "type": "CLEARING_MISMATCH", "severity": "CRITICAL", "localCapturedMinor": 8700,
  "detail": { "expectedNetMinor": 8700, "ledgerClearingNetMinor": 8750, "differenceMinor": 50, "intentIds": ["ad725d50-..."] } }
```

- **Positive difference**: the ledger shows more money still held in clearing than the payments say. Look for a refund that the
  PSP or the payment service completed but the ledger never recorded (the settlement workflow's ledger step failed before the refund), or a
  double or extra debit posted to clearing.
- **Negative difference**: the ledger shows less. Look for a ledger posting (a settlement or a compensation) for a refund the payment service
  never completed, or a capture the ledger never recorded (`ESCROW_FUNDED` missing).

Then: list the shipment's ledger transactions (`transaction_type`, `created_at`) in the ledger database, and its payment transactions and
webhook events in the payment database; find the step that exists on one side only; in the Temporal UI open `shipment-<id>` and
`settlement-<id>` to see whether a workflow failed or is still retrying (a retrying workflow resolves the mismatch by itself, which is why recent
shipments are skipped for ten minutes). Correct the missing side with an explicit, ticket-referenced action (a PSP refund or a ledger transaction),
then rerun the reconciliation and the ledger audit.

### Closing out

1. Fix the cause (not the symptom). 2. `POST /reconciliation-runs` with a window that covers the shipment's update time (the findings'
`createdAt` and the intent's `updatedAt` help). 3. Expect `OK`. 4. Add the finding ids, the cause and the action to the incident record. The
dated findings remain visible; that is intended.

## 5. Other first responses

**A courier shows `BUSY` but has no live shipment.** The relay `CourierReleaseRelay` releases a courier when its shipment reaches
`DELIVERED`, `CANCELLED` or `LOST`. If fleet was unreachable for the whole retry window (30 attempts, two seconds apart) the event was
skipped. Release by hand with the shipment id (idempotent; returns `released: false` when nobody holds it):

```bash
WF=$(curl -s http://localhost:8180/realms/routemesh/protocol/openid-connect/token -d grant_type=client_credentials \
  -d client_id=workflow-service -d client_secret=workflow-service-dev-secret | jq -r .access_token)
echo '{"shipment_id":"<shipment uuid>"}' | docker run -i --rm --network routemesh-clean_default \
  -v "$PWD/contracts/src/main/proto:/proto:ro" fullstorydev/grpcurl:v1.9.3 -plaintext -import-path /proto -proto fleet.proto \
  -H "authorization: Bearer $WF" -d @ fleet-service:9096 routemesh.fleet.v1.FleetService/ReleaseCourierByShipment
# {"released": true}  when a courier was holding the shipment;  {}  (released false) when nobody was
```

Fleet's gRPC port is not published to the host, so the call runs from a container on the Compose network (named after the Compose project:
`routemesh_default` by default, `routemesh-clean_default` in the examples). Server reflection is disabled, hence the explicit `.proto`.

**A Debezium connector is `FAILED` or events stop flowing** (`shipment-outbox`, `ledger-outbox`, `payment-outbox`, `fleet-outbox`).

```bash
curl -s http://localhost:8083/connectors/shipment-outbox/status | jq '{connector: .connector.state, tasks: [.tasks[].state]}'
curl -s -X POST 'http://localhost:8083/connectors/shipment-outbox/restart?includeTasks=true'     # 202
```

A connector registered before the owning service ran its Flyway migration fails because the publication does not exist yet; start the
service, then restart the connector. Check disk: a stalled consumer of a replication slot makes the WAL grow (the slots are capped by
`max_slot_wal_keep_size`; the outbox tables stay small because rows are deleted after seven days).

**A workflow is failed or stuck.** Open the Temporal UI (`http://localhost:8088`, namespace `routemesh`) and search by workflow id:
`shipment-<id>` (payment saga and dispatch), `dispatch-<id>-<n>` (a dispatch round), `settlement-<id>` (money distribution). A
settlement that failed with a business rejection (`REJECTED`: for example the escrow balance did not match the shipment amount) is
deliberately not retried: investigate, fix the data through the ledger and payment procedures above, and start the workflow again with the
same id and input (Temporal UI: "Start Workflow Like This"; the failed-only reuse policy allows it for a failed run).

**A service is down** (`ServiceDown`). `docker compose ps`, then `docker logs routemesh-<service>`. Containers restart automatically; a
loop usually points to its dependency (database, Keycloak JWKS, Kafka) or to memory (`ExitOnOutOfMemoryError` restarts the JVM
on heap exhaustion; the limit is 512 MB per application).

## 6. Alert map

| Alert (`docker/observability/prometheus/rules.yml`) | Severity | Go to |
|---|---|---|
| `ServiceDown` | critical | [5. A service is down](#5-other-first-responses) |
| `HighHttpErrorRate` | warning | The service's log; Tempo traces filtered by the failing service; the Platform dashboard |
| `LedgerCheckpointLag` | warning | [3. Alert LedgerCheckpointLag](#alert-ledgercheckpointlag-the-audit-is-behind) |
| `LedgerIntegrityViolation` | critical | [3. Alert LedgerIntegrityViolation](#alert-ledgerintegrityviolation--summary-violations) |
| `LedgerIntegrityRunFailed` | warning | [3](#3-ledger-integrity) (read the run's `error`) |
| `ReconciliationDiscrepancies` | critical | [4. The finding triage](#the-finding-triage) |
| `ReconciliationStale` | warning | [4](#4-psp-reconciliation-findings): is the scheduler enabled, is a run stuck, is the PSP reachable? |
| `DeadLetterGrowth` | warning | [2. Dead-letter topics](#2-dead-letter-topics) |
