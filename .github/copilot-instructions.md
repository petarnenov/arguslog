# Arguslog repository instructions

## Build, test, and lint

The repo is a polyglot monorepo: TypeScript workspaces via pnpm + Turbo, Java services via Gradle, and a Python SDK via uv/pytest.

```bash
pnpm install
make doctor                  # checks Docker, JDK 21, Node >=22, pnpm, mprocs, jq
make                         # full local stack via mprocs: api + ingest + worker + web

make build                   # Gradle build + Turbo build
pnpm lint                    # ESLint across TS workspaces
pnpm typecheck               # tsc across TS workspaces
pnpm test                    # Vitest across TS workspaces
./gradlew check              # Java unit + integration tests (Testcontainers)
make python-test             # pytest in python-sdk

pnpm e2e:install            # install Playwright Chromium once
pnpm e2e                    # Playwright suite
```

Run a single test with the native tool for that part of the monorepo:

```bash
pnpm --filter @arguslog/web test -- src/__tests__/pages/IssuesPage.test.tsx
pnpm --filter @arguslog/mcp-server test -- src/__tests__/tools.test.ts
pnpm --filter @arguslog/e2e e2e -- tests/smoke.spec.ts
./gradlew :services:api:test --tests org.arguslog.api.openapi.OpenApiContractTest
cd python-sdk && uv run pytest tests/test_client.py -k capture_exception
```

Extra repo-specific contract commands:

```bash
pnpm --filter @arguslog/sdk-browser test:pact         # refresh browser->ingest Pact files in pacts/
pnpm --filter @arguslog/mcp-server generate           # regenerate packages/mcp-server/src/generated/*
ARGUSLOG_OPENAPI_WRITE=true ./gradlew :services:api:test --tests org.arguslog.api.openapi.OpenApiContractTest
```

## High-level architecture

- `services/ingest` is the public event intake. It authenticates DSNs, applies quota/rate-limit checks, and writes accepted events to Redis Streams.
- `services/worker` is the async pipeline. It consumes Redis Streams, scrubs and fingerprints events, persists issues/events into Postgres/Timescale, symbolicates via source maps from R2/S3-compatible storage, dispatches alerts, and runs retention/tier-expiry jobs.
- `services/api` owns the authenticated REST/admin surface: orgs, projects, issues, PATs, DSNs, releases, alert rules/destinations, Slack integration, admin, and tier lookups. It is also the **only** service that owns Flyway migrations and the committed `services/api/openapi.json`.
- `packages/mcp-server` is generated from the API contract: `scripts/generate-tools.mjs` reads `services/api/openapi.json` and emits `src/generated/openapi-tools.ts`, so REST changes usually require MCP catalog regeneration too.
- `apps/web` is the authenticated dashboard. Route components live in `src/pages`, request wrappers in `src/api/*.ts`, and shared TanStack Query keys/hooks in `src/api/queries.ts`.
- `apps/landing` is a separate Vite/Mantine marketing/catalog SPA, not a shell around `apps/web`.
- SDKs are first-class products in this repo: `packages/sdk-*`, `java-sdk`, `python-sdk`, and `cli`. Wire-format changes can affect ingest, MCP prompts, CLI upload flows, and cross-SDK parity.

## Key conventions

- Java code follows a ports-and-adapters layout: `org.arguslog.<service>.<layer>`, with `application` for use cases/ports, `adapter.in.web` for controllers, `adapter.out.postgres` or other `adapter.out.*` packages for infrastructure, and `domain` for core types.
- Flyway migrations are forward-only SQL files in `services/api/src/main/resources/db/migration/` named `V<N>__<description>.sql`. Other services do not own schema migrations.
- On the dashboard, use `apiFetch` from `apps/web/src/api/client.ts` instead of ad-hoc `fetch` calls, add endpoint wrappers under `src/api/*.ts`, and reuse/invalidate keys from `src/api/queries.ts` rather than inventing page-local query keys.
- In `apps/web`, URL search params are often the canonical state for filters and pagination. Preserve that pattern instead of moving those controls into opaque local state.
- Mantine v7 and TanStack Query are the standard frontend stack. Zustand is already used for auth session state in `apps/web/src/auth/useAuthStore.ts`; do not add a second unrelated global-state pattern without a strong reason.
- Comments are intentionally sparse. Add them only for non-obvious constraints, invariants, or bug workarounds; do not narrate straightforward code.
- Tier logic is admin-grant only; there is no payment/checkout flow to extend in this repo. If a feature depends on tier limits, surface it through `PlanTier` / tier lookup abstractions instead of hardcoding tier names in business logic.
- If you change the public API shape, regenerate and commit `services/api/openapi.json`, then regenerate and commit `packages/mcp-server/src/generated/*`.
- If you change browser or Node ingest behavior, check whether the Pact snapshots in `pacts/` need regeneration.
- Local web development may require built SDK artifacts first. `make build-sdks` (or just `make`) exists because Vite resolves workspace SDKs from their built outputs.
