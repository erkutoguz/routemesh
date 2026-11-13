# ADR-0005: Zero-trust JWT validation and ownership isolation

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0001](0001-grpc-inside-rest-outside.md), [ADR-0012](0012-postgis-proof-of-delivery-and-hmac-otp.md)

## Context

Five kinds of human actors (merchant, courier, warehouse, ops, plus the platform itself as a service account) use
overlapping endpoints, and money and personal data sit behind them. A gateway that authenticates and then forwards
trusted headers makes the whole network a trust boundary: any service reachable from inside can impersonate anyone.

## Decision

- **Keycloak is the only identity provider** (realm `routemesh`, roles `OPS`, `MERCHANT`, `COURIER`, `WAREHOUSE`,
  `WORKFLOW`). Humans get tokens through the public client; services use client credentials (service accounts).
- **The gateway does not terminate identity.** It validates the token to reject garbage early and forwards the
  `Authorization` header untouched. It never derives identity or roles for downstream services, and a generic
  `/api/v1/ops/**` route does not exist: each owner service exposes its own ops endpoints.
- **Every service validates the token itself** with the shared `platform-security` module: signature through the JWKS,
  issuer, `aud=routemesh-api` and expiry, then `realm_access.roles` to `ROLE_*`. Sessions are stateless.
- **gRPC is locked by default**: every method requires the `WORKFLOW` role; no token is `UNAUTHENTICATED`, the wrong role is
  `PERMISSION_DENIED`. `location-service` overrides this because couriers stream their own position: each packet's
  `courier_id` must equal the token subject and the token expiry is rechecked per packet.
- **Ownership comes from the token, never from the request.** Merchants see rows where `merchant_id = sub`, couriers
  where `courier_id = sub`; the wallet account code is derived from the subject and role; the notification recipient
  from the subject. A caller with two actor roles, or a non-UUID subject, is rejected. Ownership is checked after the row
  lock inside the same transaction as the change, and a resource the caller does not own answers `404`, identical to a
  resource that does not exist.
- **Tokens never go in URLs.** Browsers cannot send `Authorization` from `EventSource`, so SSE clients use a
  `fetch`-based reader; the stream ends when the token expires.

## Consequences

Positive:

- Compromising or misconfiguring the gateway does not grant access: services re-verify everything.
- A forgotten check is bounded: ownership is structural (derived from `sub`), not a parameter someone must remember.
- No enumeration: `404` for foreign resources leaks nothing.

Negative:

- Every service pays for token validation and needs the JWKS reachable (cached by the decoder).
- Tokens live five minutes in the realm; long-lived clients (SSE, scripts) must renew.
- Two authorization styles to test per service (web and gRPC); each service tests the full role matrix.
- Keycloak imports its realm only when the realm does not exist, so changing clients or secrets on an installed instance
  needs a re-import ([runbook](../runbook.md#1-keycloak-realm-re-import)).

## Alternatives considered

- **Gateway authentication with trusted identity headers.** Cheaper per service, but one internal foothold becomes full impersonation.
- **Mutual TLS between services.** Orthogonal and still desirable for transport, but it does not carry user roles or ownership.
- **Opaque tokens with introspection.** A network round trip per call on every service.
