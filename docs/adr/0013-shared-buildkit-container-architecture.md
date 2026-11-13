# ADR-0013: One shared BuildKit Dockerfile for all applications

- Status: Accepted
- Date: 2026-11
- Related: [ADR-0005](0005-zero-trust-jwt-and-ownership-isolation.md)

## Context

Twelve Spring Boot applications in one Maven reactor must be packaged and run together, locally and in CI, from a clean
checkout. Twelve near-identical Dockerfiles drift apart; building the reactor twelve times wastes minutes; unpinned base
images and root users are avoidable risks; and configuration baked into images makes them environment-specific.

## Decision

- **One `docker/service.Dockerfile`** with `ARG MODULE` and `ARG HTTP_PORT`, built from the repository root.
  - A **module-independent `build` stage** compiles the whole reactor once (`mvn -DskipTests package`) with a BuildKit
    cache mount for `~/.m2`. All twelve images share that stage, so the reactor is compiled once per source change. Tests
    are compiled but not run in the image build; they run in CI.
  - An **`extract` stage** uses Spring Boot's `-Djarmode=tools extract --layers`, so dependencies, snapshot dependencies
    (our own modules) and the application are separate image layers and a code change only rebuilds the last small one.
  - The **runtime stage** is a digest-pinned `eclipse-temurin` JRE, runs as a non-root user (uid 10001), sets
    `-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError` so a container respects its memory limit and a heap exhaustion
    restarts the container instead of lingering, and declares a `HEALTHCHECK` against `/actuator/health`.
- **All base images are pinned by digest** (Maven, JRE, Postgres/PostGIS, Prometheus, Tempo, Grafana, MinIO); no `latest`
  tag exists anywhere. The cost is manual updates, which Dependabot proposes for the compose file.
- **Images carry no environment.** The jars keep their `localhost` defaults and Compose injects the in-network addresses
  through environment variables (`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SPRING_GRPC_CLIENT_CHANNEL_*_TARGET`,
  `ROUTEMESH_SECURITY_JWK_SET_URI`, ...), so the same artifact runs from an IDE and in containers. Token issuer values stay
  `http://localhost:8180/...` (what Keycloak puts in `iss`), while key and token fetches use `http://keycloak:8180`.
- **Compose profiles** make the stack composable: infrastructure by default, `apps` for the twelve applications,
  `observability` for Prometheus, Tempo and Grafana, `e2e` for the smoke test runner. Only the gateway, Keycloak and the PSP
  simulator are published to the host; every other service is reachable only on the internal network.
- **Resource and dependency discipline.** Each application is limited to 512 MB, waits for its real dependencies
  (`service_healthy`, `service_completed_successfully` for one-shot jobs) and `connect-init` registers the Debezium
  connectors only after the four outbox services (and their Flyway publications) are healthy.
- **The end-to-end runner is its own small image** (Alpine with `bash`, `curl`, `jq`, `grpcurl`) that reads the `.proto` files
  from the repository and a development-only volume with SMS texts.

## Consequences

Positive:

- One place to change packaging for every service; consistent hardening (non-root, pinned, health-checked).
- A clean checkout becomes a running platform with `docker compose --profile apps up -d`, and the same stack is exercised
  nightly in CI by the smoke test.
- Fast rebuilds: Maven cache plus layered jars.

Negative:

- The build stage compiles every module even to build one image; a change in `contracts` invalidates all twelve images (which is
  correct, since they all depend on it).
- Memory: twelve JVMs plus the infrastructure need about 9 GB of Docker memory; smaller hosts must raise the limit or run a subset.
- Running tests inside the image build was rejected (they need Docker themselves), so image builds do not prove tests pass.

## Alternatives considered

- **A Dockerfile per module.** Duplication and drift.
- **Jib or Spring Boot buildpacks.** Good tools, but a shared Dockerfile keeps the base image, user, flags and health check
  explicit and visible, and BuildKit's cache covers the build cost.
- **Baking environment into images.** Makes each image single-purpose and complicates local runs.
