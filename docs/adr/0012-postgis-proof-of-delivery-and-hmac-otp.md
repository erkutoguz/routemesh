# ADR-0012: Proof of delivery within 50 metres with an HMAC-protected OTP

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0005](0005-zero-trust-jwt-and-ownership-isolation.md), [ADR-0006](0006-inbox-and-dead-letter-topics.md)

## Context

A delivery is the moment the escrow is released, so it must be hard to fake and impossible to alter afterwards. Three
things must hold: the courier was actually at the delivery point, the recipient accepted the parcel, and the evidence is
stored immutably. A courier far from the address should not be able to guess the recipient's code, and the code must
not leak through our own infrastructure.

## Decision

- **Location rule enforced in the database.** `deliver` runs in a single transaction: lock the shipment, replay idempotent
  duplicates, check the state is `OUT_FOR_DELIVERY`, validate fields and file ownership, run a **side-effect-free
  distance pre-check**, verify the OTP, then insert the proof with
  `INSERT ... SELECT ... WHERE ST_DWithin(delivery_location, reported_point, 50)` and move the shipment to `DELIVERED`. A row
  count of zero means "too far" (`422` with the distance), so a race between the pre-check and the insert cannot let a far
  point through; `CHECK (distance_meters <= 50)` backs it up.
- **The pre-check protects the OTP.** Distance is verified before the OTP is consumed, so a courier who is not at the address cannot
  burn the recipient's five attempts.
- **The OTP is issued by `notification-service`, not by a workflow.** When the shipment becomes `OUT_FOR_DELIVERY` the consumer
  calls `IssueDeliveryOtp` (a `WORKFLOW`-only gRPC) and hands the six-digit code straight to the SMS channel as a local variable.
  The database stores only `HMAC-SHA256(server secret, shipmentId:code)`, with a 24 hour
  expiry and an attempt counter (maximum five). The code never appears in Kafka, in a column, in a log line or in a notification row.
- **When a code was issued it is mandatory** for `deliver`: missing or wrong is `403`, locked or expired `423`, and issuing a
  new code resets the counter and invalidates the old one. The attempt counter is incremented in its own `REQUIRES_NEW`
  transaction so that a failed delivery request cannot roll the count back.
- **Evidence is files plus a record.** Photos and signatures are uploaded through the service (never direct to storage) as JPEG or
  PNG, at most 5 MB and five files per shipment, detected from the magic bytes rather than the client's content type, stored in
  a private MinIO bucket under `pod/<shipment>/<file>`, with the SHA-256 and size recorded. The MinIO user can read and write that bucket but
  not delete. The proof, its files and the history are immutable.
- **Reading is scoped.** The merchant (owner), `OPS` and the assigned courier can read the proof; merchant and ops only see files attached to a
  recorded proof, not half-finished uploads.
- **Development convenience, production guard.** The end-to-end test needs the OTP, so a development-only sink file
  (`routemesh.notification.sms.sink-file`) can capture SMS texts; the application refuses to start with it under the `prod` profile.

## Consequences

Positive:

- The rule is true by construction (one atomic statement) rather than by careful ordering of service code.
- A leaked database or log does not reveal a usable code; a rogue courier cannot brute-force it from afar.
- Delivery is idempotent: the same key and the same evidence returns the original result, a different payload is `409`.

Negative:

- GPS can be spoofed on the device; the 50 metre rule trusts the reported position. Cross-checking with the location service's last
  known position is a possible extension, not implemented.
- If the SMS is delivered but the consumer's transaction fails, the retry issues a second code and the first stops working.
- Services that need PostGIS require the PostGIS image, including in tests.

## Alternatives considered

- **Check the distance in application code.** Racy and easy to bypass by a code path that forgets.
- **Store the OTP, or encrypt it reversibly.** A leak would expose live codes.
- **Pre-signed direct uploads to MinIO.** Skips content-type verification and ownership checks.
