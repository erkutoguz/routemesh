# syntax=docker/dockerfile:1.7
#
# RouteMesh Console. Bağlam web/ klasörüdür:
#   docker build -t routemesh/web:dev web
# NEXT_PUBLIC_* değerleri derleme anında tarayıcı paketine gömülür (tarayıcı gateway ve Keycloak'a konak adresinden gider);
# PSP_SIMULATOR_URL ise sunucunun /psp-sim yeniden yazımının iç ağ hedefidir.

# node:22-alpine (22.23.5)
FROM node@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS deps
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci

FROM deps AS build
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ARG NEXT_PUBLIC_KEYCLOAK_URL=http://localhost:8180
ARG PSP_SIMULATOR_URL=http://psp-simulator:8085
ARG NEXT_PUBLIC_MAP_TILE_URL
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL} \
    NEXT_PUBLIC_KEYCLOAK_URL=${NEXT_PUBLIC_KEYCLOAK_URL} \
    PSP_SIMULATOR_URL=${PSP_SIMULATOR_URL} \
    NEXT_TELEMETRY_DISABLED=1
COPY . .
RUN npm run build

FROM node@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS runtime
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOSTNAME=0.0.0.0 \
    PORT=3001
WORKDIR /app
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
USER node
EXPOSE 3001
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=6 \
  CMD wget -q --spider http://127.0.0.1:3001/ || exit 1
CMD ["node", "server.js"]
