# ADR-0014: A browser-only web console with Authorization Code + PKCE and a fetch-based SSE reader

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0001](0001-grpc-inside-rest-outside.md), [ADR-0005](0005-zero-trust-jwt-and-ownership-isolation.md), [ADR-0007](0007-keyset-cursor-pagination.md)

## Context

The platform had a complete API and no face. The console has to serve four very different users (merchant, warehouse,
courier, operations) against the same gateway, and it must not weaken the zero-trust rule that every service validates the
caller's own JWT. Two constraints shape the design: the browser has to call the gateway directly with a bearer token, and the live
tracking stream (`/api/v1/tracking/{id}/stream`) is Server-Sent Events behind an `Authorization` header.

## Decision

- **Next.js (App Router), TypeScript and Tailwind in `web/`, no backend-for-frontend.** The pages are client components that talk
  to the gateway with a small typed `fetch` client (bearer token, `Idempotency-Key`, RFC 9457 problem mapping, one silent refresh
  and retry on `401`). State is React state and hooks; there is no state library.
- **Authorization Code + PKCE (S256) in the browser** against the existing public client `routemesh-web`, using `oidc-client-ts`.
  Keycloak enforces `pkce.code.challenge.method=S256` on the client, so a plain code challenge is refused. The console asks for the
  `openid` scope only and reads the roles from `realm_access.roles` in the access token, the claim the gateway and every service
  already enforce; the UI hides what a role cannot do, the services refuse it.
- **Token storage is `sessionStorage`, renewed with the refresh token.** The access token is only read through the user manager
  and attached per request; it is never put in a URL, a cookie or `localStorage`. Closing the tab ends the session. Keeping tokens
  strictly in memory was considered and rejected: it signs the user out on every reload, which for a courier in the middle of a
  delivery is worse than the extra exposure `sessionStorage` has to script injection (see Consequences).
- **Deep links survive sign-in.** The path being visited is carried in the OIDC `state` (`returnTo`) and restored after the
  callback, after being checked to be a same-origin path. An explicit sign-out marks the session as signing out so that no role
  page starts a new login while Keycloak is ending the old one.
- **SSE through `fetch` and `ReadableStream`.** `EventSource` cannot send an `Authorization` header, and the tracking endpoint
  is protected like every other. `src/lib/sse.ts` therefore parses the event stream itself (chunk boundaries, CRLF, comments,
  multi-line data) and reconnects with exponential backoff. The gateway ends a stream when the token expires, so reconnecting with
  the renewed token is the normal path; only an `end` event with reason `ENDED` stops the reader.
- **The gateway allows the web origin, nothing else.** CORS is configured in the gateway's security chain so that preflight requests are
  answered before authentication; the allowed origin is `routemesh.gateway.cors.allowed-origins` (default `http://localhost:3001`),
  with the methods and headers the console needs (`Authorization`, `Content-Type`, `Idempotency-Key`).
- **Thin REST over existing service logic where the browser had no door.** `pricing-service` gets `POST /api/v1/quotes` and
  `GET /api/v1/quotes/{id}` over the existing `QuoteService`, and `location-service` gets `POST /api/v1/couriers/me/location`
  over the existing `ingest` path (the same epoch and sequence rule, the courier id always taken from the token). The gateway routes both.
- **Packaged like the rest.** `web/Dockerfile` is a pinned multi-stage Node 22 Alpine build that runs Next's standalone output as a
  non-root user with a health check; the `web` Compose profile publishes it on `127.0.0.1:3001`. Browser-facing URLs are baked in at
  build time; the one server-side setting, the development `/psp-sim` rewrite to the PSP simulator, is a build argument.

## Consequences

- **Good.** No new server component to secure or scale, and no session state: the gateway and the services see exactly the same
  bearer token as any other client, so ownership isolation and role checks cannot be bypassed by the UI. PKCE removes the
  need for a client secret that a browser cannot keep.
- **Good.** The reader is a small pure parser with unit tests, independent of React, and it makes reconnecting after token expiry a
  normal code path rather than an error.
- **Bad.** A token in `sessionStorage` can be read by any script that runs in the page, so an XSS bug would expose it. The mitigations
  are no third-party scripts, no `dangerouslySetInnerHTML`, short access token lifetimes and nothing long-lived in storage beyond the
  tab; a production deployment should add a strict `Content-Security-Policy` at the edge, which this reference does not.
- **Bad.** Anything that needs a secret or a server-side join (for example the escrow balance, which the wallet API does not
  expose) cannot be done in the browser; the console shows only what the existing APIs return.
- **Bad.** The password grant on `routemesh-web` stays enabled for the end-to-end script; it is a development convenience and must
  be switched off for any real deployment.
- Keycloak imports its realm only on the first start of a database, so an existing environment needs the client updated by hand
  (see the [web README](../../web/README.md#auth)).

## Alternatives considered

- **Backend-for-frontend with a server-side session.** Keeps tokens out of the browser entirely and is the better answer for a
  public product. Rejected here because it adds a stateful tier and a second place that must forward identity, while the gateway is designed to
  be the only edge and never to derive identity. It is the natural next step if the console is ever exposed beyond a trusted network.
- **`EventSource` with the token in the query string.** Works without a custom reader, but puts a bearer token into URLs, access
  logs and browser history. Rejected outright.
- **A polyfill library for header-capable SSE.** Would save the parser, but replaces forty lines we can test with a dependency whose
  reconnect and token behaviour we would still have to wrap.
- **Tokens only in memory with a hidden iframe or silent renew on load.** Avoids web storage but depends on third-party cookie
  behaviour that browsers keep restricting.
- **Hand-written PKCE.** About a hundred and twenty lines of security-sensitive code; a small, maintained library is safer.
