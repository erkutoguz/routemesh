# RouteMesh Console

Next.js (App Router), TypeScript and Tailwind CSS front end for the four RouteMesh roles: `MERCHANT`, `WAREHOUSE`, `COURIER` and `OPS`.
The decisions behind it are in [ADR-0014](../docs/adr/0014-web-console-oidc-pkce-and-fetch-sse.md).

## Quickstart

The console runs on port 3001 because Grafana uses 3000.

```bash
# whole platform plus the console, from the repository root
cd docker
docker compose --profile apps --profile web up -d --wait
# open http://localhost:3001
```

The `web` profile builds `web/Dockerfile` and depends on services of the `apps` profile, so the two profiles go together.

For development, run the backend as above (without the `web` profile) and start the dev server:

```bash
cd web
npm install
npm run dev
```

Sign in with a seeded user: `merchant`, `warehouse`, `courier` or `ops`, password `<name>-dev`. Each user lands on the console of their realm role; a
user with several roles gets a chooser.

| Variable | Default | Used for |
|---|---|---|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | Gateway base URL (build time, in the browser bundle) |
| `NEXT_PUBLIC_KEYCLOAK_URL` | `http://localhost:8180` | OIDC authority (build time) |
| `PSP_SIMULATOR_URL` | `http://localhost:8085` (`http://psp-simulator:8085` in the image) | Target of the server-side `/psp-sim` rewrite |

## Structure

```text
src/
  app/                 routes: / (role chooser), /callback, /merchant/**, /warehouse, /courier, /ops
  components/          RoleShell, TrackingMap (Leaflet), DeliverForm, PaymentPanel, ReconciliationPanel, IntegrityPanel ...
  lib/
    auth/              oidc-client-ts setup, AuthProvider, role parsing, returnTo
    api/               typed fetch client + merchant.ts, operations.ts, ops.ts endpoint modules
    sse.ts             fetch + ReadableStream event-stream parser with reconnect
    tracking.ts, geo.ts, money.ts, places.ts, sampleProof.ts, useCursorList.ts, usePoll.ts
```

| Layer | Choice |
|---|---|
| State | React state and hooks; one context for auth |
| Data | Typed `fetch` client: bearer token, `Idempotency-Key`, RFC 9457 errors, one refresh and retry on `401` |
| Lists | Keyset cursors from the API, "Load more" (`useCursorList`) |
| Map | Leaflet and react-leaflet on the OpenStreetMap standard tile layer (no API key; light use only, see the OSM tile usage policy) |
| Money | `amountMinor` integers formatted with integer arithmetic only |

## Consoles

- **Merchant** (`/merchant/quote`, `/shipments`, `/shipments/[id]`, `/wallet`): price a shipment with preset Istanbul places or free coordinates, create it with an
  idempotency key, then complete the payment through the PSP simulator with one click. The shipment page streams live tracking (the
  courier marker, a 50 m ring around the drop-off, approach banners). The wallet shows the available balance and the double-entry ledger entries;
  funds in escrow are not exposed by the API and are not shown.
- **Warehouse** (`/warehouse`): shipments waiting for the warehouse with **Receive** and **Mark ready**.
- **Courier** (`/courier`): go online, accept or reject offers, then work the active delivery. GPS pings use a per-session epoch and an increasing
  sequence; quick buttons place the courier at the depot, 120 m, 15 m or 0 m from the drop-off, and a map click reports any position.
  **Depart** moves the shipment to `OUT_FOR_DELIVERY`. The delivery form uploads a photo (and an optional signature), "Use sample" generates a PNG in the
  browser, and a courier further than 50 m away sees the backend's `422` in the error banner.
- **Operations** (`/ops`): reconciliation summary, **Run reconciliation now** (`202`, the summary refreshes while it runs) and a filtered, paged findings list;
  ledger integrity summary with the latest checkpoint, **Check now** (incremental) and **Full audit**, and its findings.

Development notes:

- The pickup point shown to couriers is the depot constant in `src/lib/geo.ts`.
- The recipient OTP is written to the SMS sink file: `docker exec routemesh-notification-service tail -n 3 /sms/sink.jsonl` (in Git Bash prefix it with
  `MSYS_NO_PATHCONV=1`).
- The `checkout_url` the PSP simulator returns has no page behind it; the browser completes the payment through the same-origin `/psp-sim` rewrite, so the
  simulator needs no CORS configuration.

## Auth

Authorization Code + PKCE (S256) against the `routemesh-web` public client, using `oidc-client-ts`. Tokens live in `sessionStorage` and are renewed silently; there is no
backend-for-frontend. Roles are read from `realm_access.roles` in the access token, the same claim the gateway enforces. Deep links survive sign-in (`returnTo`
in the OIDC state), and signing out does not race a new login.

The gateway allows the origin `http://localhost:3001` (`routemesh.gateway.cors.allowed-origins`).

An existing Keycloak database keeps the old `routemesh-web` client, because the realm file is imported only on first start. Either recreate the Keycloak database, or
add `http://localhost:3001/*` to the client's redirect URIs and web origins and set "Proof Key for Code Exchange Code Challenge Method" to `S256` in the admin console.

## Checks

```bash
npm run lint
npm run typecheck
npm test
npm run build
docker build -t routemesh/web:dev .
```
