# Arguslog

Multi-tenant error tracking platform. Sentry-like, hosted on Railway.

## Stack

- **Frontend** — Vite + React 19 + React Router v7 + TanStack Query v5 + Mantine v7 + Vitest
- **Backend** — Java 21 + Spring Boot 3.4 (microservices: `ingest`, `worker`, `api`)
- **Storage** — Postgres + TimescaleDB + Redis Streams + Cloudflare R2
- **Auth** — Keycloak 25 (OIDC + PKCE)
- **Monorepo** — Turborepo + pnpm workspaces + Gradle composite build

## Layout

```
apps/web/                      # React/Vite dashboard
apps/landing/                  # Vite + Mantine marketing site (live SDK catalog)
services/api/                  # public REST + admin
services/ingest/               # public event endpoint
services/worker/               # Redis Streams consumer
services/keycloak/realm/       # Keycloak realm export
packages/sdk-core/             # @arguslog/sdk-core — shared transport, scope, scrubber
packages/sdk-browser/          # @arguslog/sdk-browser — vanilla JS/TS browser SDK
packages/sdk-node/             # @arguslog/sdk-node — Node.js SDK + Express adapter
packages/sdk-react/            # @arguslog/sdk-react — ErrorBoundary + useArguslog
packages/sdk-react-native/     # @arguslog/sdk-react-native — RN-aware integrations
packages/sdk-nextjs/           # @arguslog/sdk-nextjs — App/Pages Router + instrumentation hook
packages/sdk-angular/          # @arguslog/sdk-angular — ErrorHandler + provideArguslog
packages/sdk-vue/              # @arguslog/sdk-vue — Vue 3 plugin + composable + ErrorBoundary
packages/sdk-web3/             # @arguslog/sdk-web3 — viem/ethers/Solana/Anchor/wagmi error decoding + auto-breadcrumbs
packages/eslint-config/        # shared ESLint config
packages/tsconfig/              # shared tsconfig presets
java-sdk/                      # org.arguslog:arguslog-java-sdk (Spring Boot autoconfig)
python-sdk/                    # arguslog (PyPI) — Python 3.9+ SDK with stdlib transport
cli/                           # @arguslog/cli — releases + sourcemap upload
e2e/                           # Playwright suites
infra/docker/                  # docker-compose for local dev
```

Flyway migrations are owned by `services/api` and live in
`services/api/src/main/resources/db/migration/`. Other services run with
`flyway.enabled=false`.

### Naming

Java packages and Maven `groupId` use `org.arguslog.*` — reverse-DNS of
the project domain `arguslog.org`. The product name is still **Arguslog**;
`arguslog` only appears in coordinates and the public domain (the short
domain `arguslog.org` was unavailable at registration time).

## Local dev

The full stack — infra + 3× Spring Boot services + web — runs from a single
command via [`mprocs`](https://github.com/pvolok/mprocs):

```bash
brew install mprocs           # one-time
pnpm install                  # one-time
make doctor                   # verify prerequisites (docker, pnpm, java, mprocs)
make dev                      # bring up everything
```

`make dev` first runs `docker compose up -d --wait` so JVM services see a
healthy Postgres / Redis / Keycloak / MinIO from boot, then opens an mprocs
TUI with one panel per process: `ingest` (`:8080`), `api` (`:8081`),
`worker` (`:8082`), `web` (`:5173`), plus a manual `infra-logs` panel.

In mprocs: `Tab` switches panels, `r` restarts the focused process, `s`
starts a manual one, `q` quits and gracefully stops everything. Infra keeps
running across mprocs sessions; tear it down with `make down`.

### Make targets

|                                            |                                                                 |
| ------------------------------------------ | --------------------------------------------------------------- |
| `make dev`                                 | full stack (infra + JVM services + web)                         |
| `make up` / `down`                         | infra only (compose up `--wait` / down)                         |
| `make logs` / `ps`                         | tail / inspect infra                                            |
| `make api`                                 | `arguslog-api` foreground (`:8081`)                             |
| `make ingest`                              | `arguslog-ingest` foreground (`:8080`)                          |
| `make worker`                              | `arguslog-worker` foreground (`:8082`)                          |
| `make web`                                 | Vite dev server (`:5173`)                                       |
| `make build`                               | Gradle + Turbo full build                                       |
| `make lint` / `typecheck` / `test` / `e2e` | quality gates                                                   |
| `make clean` / `reset`                     | drop build artifacts / nuke containers + volumes + node_modules |
| `make doctor`                              | check prerequisites                                             |
| `make help`                                | list all targets                                                |

### Manual route (without `make` / `mprocs`)

```bash
docker compose -f infra/docker/docker-compose.yml up -d
pnpm dev                                                       # web + SDK watch
./gradlew :services:api:bootRun :services:ingest:bootRun :services:worker:bootRun
```

## Tests

```bash
pnpm test                   # JS unit (vitest, all workspaces)
pnpm test:coverage          # same, with v8 coverage gate
pnpm e2e                    # Playwright (run pnpm e2e:install once first)
./gradlew check             # Java unit + integration (Testcontainers)
make python-test            # pytest under python-sdk/ (uv-managed venv)
```

## SDKs

The platform ships first-class SDKs across the JS, JVM, and Python
ecosystems. The dashboard's project-create dropdown and the marketing
landing page are both fed live from `/api/v1/platforms` so the catalog
stays in lockstep with what's actually shipped.

| Runtime             | Package                          | Source                       |
| ------------------- | -------------------------------- | ---------------------------- |
| Browser (JS/TS)     | `@arguslog/sdk-browser`          | `packages/sdk-browser/`      |
| React               | `@arguslog/sdk-react`            | `packages/sdk-react/`        |
| Next.js             | `@arguslog/sdk-nextjs`           | `packages/sdk-nextjs/`       |
| Angular             | `@arguslog/sdk-angular`          | `packages/sdk-angular/`      |
| Vue 3               | `@arguslog/sdk-vue`              | `packages/sdk-vue/`          |
| React Native        | `@arguslog/sdk-react-native`     | `packages/sdk-react-native/` |
| Node.js             | `@arguslog/sdk-node`             | `packages/sdk-node/`         |
| Java / Spring       | `org.arguslog:arguslog-java-sdk` | `java-sdk/`                  |
| Python 3.9+         | `arguslog` (PyPI)                | `python-sdk/`                |
| Web3 (EVM + Solana) | `@arguslog/sdk-web3`             | `packages/sdk-web3/`         |

A standalone install + quickstart index for every SDK lives in
[`docs/sdks.md`](docs/sdks.md). The Web3 add-on layers on top of any
JS-runtime SDK and decodes wallet / chain / contract / Anchor errors
from viem, ethers v6, `@solana/web3.js`, Anchor, wagmi, and
WalletConnect into searchable Arguslog issues.

## License

MIT (see LICENSE). Java SDK is Apache-2.0.
