# Argus

Multi-tenant error tracking platform. Sentry-like, hosted on Railway.

## Stack

- **Frontend** — Vite + React 19 + React Router v7 + TanStack Query v5 + Mantine v7 + Vitest
- **Backend** — Java 21 + Spring Boot 3.3 (microservices: `ingest`, `worker`, `api`)
- **Storage** — Postgres + TimescaleDB + Redis Streams + Cloudflare R2
- **Auth** — Keycloak 25 (OIDC + PKCE)
- **Monorepo** — Turborepo + pnpm workspaces + Gradle composite build

## Layout

```
apps/        # web, marketing, docs (React/Vite)
services/    # ingest, worker, api (Spring Boot)
packages/    # sdk-browser, sdk-react, api-client, ui, eslint-config, tsconfig
java-sdk/    # Java/Spring Boot SDK
cli/         # @argus/cli — releases & sourcemaps
infra/       # docker-compose, railway, flyway, k6
e2e/         # Playwright tests
```

## Local dev

```bash
# Install JS deps
pnpm install

# Start infra (Postgres+Timescale, Redis, Keycloak, MinIO)
docker compose -f infra/docker/docker-compose.yml up -d

# Run all services in dev
pnpm dev

# In another shell — Spring services
./gradlew :services:api:bootRun :services:ingest:bootRun :services:worker:bootRun
```

## Tests

```bash
pnpm test                   # JS unit + integration
pnpm e2e                    # Playwright e2e
./gradlew check             # Java unit + integration (Testcontainers)
```

## License

MIT (see LICENSE). Java SDK is Apache-2.0.
