# RouteMesh Console

Next.js (App Router), TypeScript and Tailwind CSS front end for the four RouteMesh roles: MERCHANT, WAREHOUSE, COURIER and OPS.

## Run

The backend must be up (Keycloak on 8180, api-gateway on 8080). The console runs on port 3001 because Grafana uses 3000.

```bash
npm install
npm run dev
```

Open http://localhost:3001 and sign in with a seeded user (`merchant`, `warehouse`, `courier`, `ops`; password `<name>-dev`). Each user lands on the console of their realm role.

| Variable | Default |
|---|---|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` |
| `NEXT_PUBLIC_KEYCLOAK_URL` | `http://localhost:8180` |
| `PSP_SIMULATOR_URL` | `http://localhost:8085` (server side, used by the `/psp-sim` rewrite) |

## Merchant console

- `/merchant/quote`: price a shipment (preset Istanbul places or free coordinates), create it with an `Idempotency-Key`, then complete the payment through the PSP simulator with one click. The browser calls the simulator through the same-origin `/psp-sim` rewrite, so the simulator needs no CORS configuration. The `checkout_url` the simulator returns has no page behind it.
- `/merchant/shipments`: CQRS search list with a status filter and keyset "Load more".
- `/merchant/shipments/[id]`: live tracking. `EventSource` cannot send an `Authorization` header, so `src/lib/sse.ts` reads the stream with `fetch` and reconnects with backoff (the gateway ends streams when the token expires).
- `/merchant/wallet`: wallet balance and the double-entry ledger history.

## Auth

Authorization Code + PKCE (S256) against the `routemesh-web` public client, using `oidc-client-ts`. Tokens live in `sessionStorage` and are renewed silently with the refresh token; there is no backend-for-frontend. Roles are read from `realm_access.roles` in the access token, the same claim the gateway enforces.

The gateway allows the origin `http://localhost:3001` (`routemesh.gateway.cors.allowed-origins`).

An existing Keycloak database keeps the old `routemesh-web` client, because the realm file is imported only on first start. Either recreate the Keycloak database, or add `http://localhost:3001/*` to the client's redirect URIs and web origins and set the advanced setting "Proof Key for Code Exchange Code Challenge Method" to `S256` in the admin console.

## Warehouse and courier consoles

- `/warehouse`: shipments waiting for the warehouse (PAID, AT_WAREHOUSE, READY_FOR_DISPATCH) from the warehouse search endpoint, with **Receive** and **Mark ready** actions (one `Idempotency-Key` per click).
- `/courier`: go online, accept or reject offers, then work the active delivery. GPS telemetry goes through `POST /api/v1/couriers/me/location` with a per-session epoch and an increasing sequence; quick buttons place the courier at the depot, 120 m, 15 m or 0 m from the drop-off, and a map click reports any position. **Depart** moves the shipment to OUT_FOR_DELIVERY. The delivery form uploads a photo (and optional signature) and submits the recipient OTP. "Use sample" generates a PNG in the browser. A courier more than 50 m away gets the backend's HTTP 422 geofence problem in the error banner.
- The depot (pickup) point is a constant in `src/lib/geo.ts` and matches the depot used by the workflow and location services.
- Development OTP: the recipient SMS is written to the sink file, `docker exec routemesh-notification-service tail -n 3 /sms/sink.jsonl` (in Git Bash prefix the command with `MSYS_NO_PATHCONV=1`).
- Deep links survive sign-in: the current path is kept in the OIDC state (`returnTo`) and restored after the callback.

## Checks

```bash
npm run lint
npm run typecheck
npm test
npm run build
```
