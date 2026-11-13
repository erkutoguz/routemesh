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

## Auth

Authorization Code + PKCE (S256) against the `routemesh-web` public client, using `oidc-client-ts`. Tokens live in `sessionStorage` and are renewed silently with the refresh token; there is no backend-for-frontend. Roles are read from `realm_access.roles` in the access token, the same claim the gateway enforces.

The gateway allows the origin `http://localhost:3001` (`routemesh.gateway.cors.allowed-origins`).

An existing Keycloak database keeps the old `routemesh-web` client, because the realm file is imported only on first start. Either recreate the Keycloak database, or add `http://localhost:3001/*` to the client's redirect URIs and web origins and set the advanced setting "Proof Key for Code Exchange Code Challenge Method" to `S256` in the admin console.

## Checks

```bash
npm run lint
npm run typecheck
npm test
npm run build
```
