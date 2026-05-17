# Arguslog

**Less dashboard, more dialogue — error tracking, designed for agents.**

Open-source, self-hostable, multi-tenant error tracking platform with an
MCP-first surface: triage happens in the same chat where you're already
coding, not behind three filter dropdowns. Wire format, DSN scheme, and
SDK shape are all Arguslog-native — no vendor compatibility layer to
inherit. Run it on your own infrastructure, or use the hosted instance at
[arguslog.org](https://arguslog.org) for free.

Read. Eval. Triage. Loop. — every issue, event, and breadcrumb is one MCP
tool-call away through [`@arguslog/mcp-server`](packages/mcp-server).

## Quick start — 3 seconds with your AI agent

1. Sign up at [arguslog.org](https://arguslog.org) — GitHub or Google.
2. Create your org + first project in the onboarding wizard.
3. Open the **Connect** page. Your project's DSN and a Personal Access Token
   are auto-provisioned on first visit and inlined into every prompt — no
   manual "generate / copy" round-trip.
4. Pick your AI coding agent and copy the magic prompt:
   - **Claude Code · Cursor · Codex · GitHub Copilot (Chat + CLI) · Windsurf · Continue** are all supported. Each prompt writes to the agent's canonical config (Claude Code/Copilot CLI `.mcp.json`, Cursor `.cursor/mcp.json`, Codex `~/.codex/config.toml`, Copilot Chat `.vscode/mcp.json`, Windsurf `~/.codeium/windsurf/mcp_config.json`, Continue `.continue/mcpServers/arguslog.yaml`).
5. Paste into the agent. It detects your stack (`package.json` / `pyproject.toml`
   / `build.gradle`), installs `@arguslog/sdk-<platform>` at the pinned version
   from the platforms catalog, drops `init({ dsn })` in the right entry point,
   and registers the Arguslog MCP server in the agent's own config file. The
   credentials live at the exact key the agent reads (`headers.Authorization`
   for HTTP transports, `env.ARGUSLOG_PAT` for stdio) — no placeholders to
   patch later. Rotate or revoke any time from `/me/tokens` or by calling the
   `delete_me_tokens` / `create_me_tokens` MCP tools.
6. After install, your agent discovers four canned **Read · Eval · Triage · Loop**
   workflows via the MCP `prompts/` capability: `arguslog_triage_loop`,
   `arguslog_release_postmortem`, `arguslog_regression_check`,
   `arguslog_investigate_issue`. Claude Code / Cursor / Continue surface them
   automatically; the Connect → Workflows tab mirrors them as copy-paste for the
   rest. All mutating tools sit behind an explicit user OK — no auto-apply.

Self-hosting? Skip to [SELF_HOSTING.md](SELF_HOSTING.md).

## What it does

- Captures uncaught exceptions, log records, and breadcrumbs from JS, JVM, and
  Python codebases via first-class SDKs.
- Fingerprints + groups events into issues, persists them in
  Postgres+TimescaleDB, and exposes a React dashboard for triage.
- Sends real-time alerts to Slack, Telegram, generic webhooks, or email via
  configurable rules with throttling.
- Closes the triage loop in Slack — install the Arguslog Slack app and run
  `/arguslog issues|issue <id>|resolve <id>|release <ver>|set-project <slug>`
  from any channel; mutations broadcast in-channel so the team sees them
  without a separate ping.
- Resolves minified JS stack traces back to original source via uploaded
  source maps (CLI `argus releases upload-sourcemaps`).
- Multi-tenant — orgs / projects / members / roles — so a single instance can
  serve a team or a whole company.

## Tiers

The runtime supports four configurable tiers — `regular`, `silver`, `gold`,
`platinum` — that gate per-month event counts, project count, member count,
and retention window. On the hosted arguslog.org instance every new user
starts on `regular`; admins (env-allowlist `ARGUSLOG_PLATFORM_ADMINS`) hand
out elevated tiers as needed. On a self-hosted instance you set
`ARGUSLOG_DEFAULT_TIER=platinum` and everyone is uncapped by default; the
admin grant flow is still available if you ever want to differentiate.

There is no payment surface in the code — no Stripe, no checkout, no
subscriptions. Tier elevation is admin-grant only.

## Stack

- **Frontend** — Vite + React 19 + React Router v7 + TanStack Query v5 + Mantine v7 + Vitest
- **Backend** — Java 21 + Spring Boot 3.4 (microservices: `ingest`, `worker`, `api`)
- **Storage** — Postgres + TimescaleDB + Redis Streams + S3-compatible object store (R2 / MinIO)
- **Auth** — Keycloak 25 (OIDC + PKCE)
- **Monorepo** — Turborepo + pnpm workspaces + Gradle composite build

## Layout

```
apps/web/                      # React/Vite dashboard
apps/landing/                  # Vite + Mantine marketing site (live SDK catalog)
apps/browser-extension/        # Chromium MV3 operator console for Arguslog MCP
services/api/                  # public REST + admin endpoints
services/ingest/               # public event endpoint
services/worker/               # Redis Streams consumer + cron jobs
services/keycloak/realm/       # Keycloak realm template
packages/sdk-core/             # @arguslog/sdk-core — shared transport, scope, scrubber
packages/sdk-browser/          # @arguslog/sdk-browser — vanilla JS/TS browser SDK
packages/sdk-node/             # @arguslog/sdk-node — Node.js SDK + Express adapter
packages/sdk-react/            # @arguslog/sdk-react — ErrorBoundary + useArguslog
packages/sdk-react-native/     # @arguslog/sdk-react-native — RN-aware integrations
packages/sdk-nextjs/           # @arguslog/sdk-nextjs — App/Pages Router + instrumentation hook
packages/sdk-angular/          # @arguslog/sdk-angular — ErrorHandler + provideArguslog
packages/sdk-vue/              # @arguslog/sdk-vue — Vue 3 plugin + composable + ErrorBoundary
packages/sdk-web3/             # @arguslog/sdk-web3 — viem/ethers/Solana/Anchor/wagmi error decoding
packages/mcp-server/           # @arguslog/mcp-server — Model Context Protocol surface for Claude / agents
java-sdk/                      # org.arguslog:arguslog-java-sdk (Spring Boot autoconfig)
python-sdk/                    # arguslog (PyPI) — Python 3.9+ SDK
cli/                           # @arguslog/cli — releases + sourcemap upload
e2e/                           # Playwright suites
infra/docker/                  # docker-compose for local dev
```

Flyway migrations are owned by `services/api` and live in
`services/api/src/main/resources/db/migration/`. Other services run with
`flyway.enabled=false`.

### Naming

Java packages and Maven `groupId` use `org.arguslog.*` — reverse-DNS of
the project domain `arguslog.org`. The product name is **Arguslog**;
`arguslog` is the short slug used in coordinates and the public domain.

## Self-hosting

A working docker-compose stack — postgres+timescale, redis, keycloak, minio,
mailhog — lives in `infra/docker/docker-compose.yml`. See
[SELF_HOSTING.md](SELF_HOSTING.md) for the step-by-step runbook
(images, env vars, Keycloak first-boot admin, TLS, backups).

## Local dev

The full stack — infra + 3× Spring Boot services + web — runs from a single
command via [`mprocs`](https://github.com/pvolok/mprocs):

```bash
git clone https://github.com/petarnenov/arguslog.git && cd arguslog
make doctor                   # verify prerequisites; prints OS-specific install commands for anything missing
make                          # bring up everything (alias for `make dev`)
```

Prereqs are Docker, JDK 21, Node ≥22, pnpm, mprocs, jq. `make doctor` checks
all of them and prints the right `brew install` / `apt install` / `sdk install`
command for each miss — re-run until it's all green, then `make`.

Optional: set `GITHUB_CLIENT_ID` / `GOOGLE_CLIENT_ID` / `GITLAB_CLIENT_ID`
(and matching `*_SECRET`s) in `.env.local` to enable social-login buttons
on the Keycloak sign-in page. See [SELF_HOSTING.md](SELF_HOSTING.md#social-login-github--google--gitlab)
for the OAuth-app registration steps + per-env callback URLs.

`make` (the default goal) runs `docker compose up -d --wait` so JVM services
see a healthy Postgres / Redis / Keycloak / MinIO (Keycloak 26.1) from boot,
then opens an mprocs TUI with one panel per process: `ingest` (`:8080`),
`api` (`:8081`), `worker` (`:8082`), `web` (`:5173`), plus a manual
`infra-logs` panel.

### Demo data

`make demo` is the one-command "I just want to see Arguslog work" flow.
It chains `reset → fresh → make → seed`:

```bash
make demo
# in another terminal, optionally:
tail -f /tmp/arguslog-seed.log    # watch the seed banner with login URL
```

After mprocs starts, a background watcher polls the api; once healthy it
runs the seed script which creates:

- a `demo@arguslog.local / demo` Keycloak user
- a Demo Org + Demo App project
- 8-12 synthetic events spread across the last 14 days (non-empty sparkline)

Output goes to `/tmp/arguslog-seed.log` so it doesn't fight the TUI for the
terminal. Sign in at <http://localhost:5173> with the demo credentials.

If you already have the stack up and just want to seed (without resetting):

```bash
make seed                         # in another terminal alongside `make`
```

The seed is idempotent — re-runs short-circuit on existing user/org/project.

> **Keycloak state migrations**: `make demo` (via `make reset`) wipes the
> Postgres volume. If you only have `make seed` running into "invalid admin"
> or "client not found" errors on an old stack, run `make fresh && make` once
> to drop the volume and re-import the realm with the `arguslog-seed` client.

### Cross-device dev (phone on your LAN)

```bash
DEV_HOST=192.168.0.42 make    # substitute your LAN IP
```

The Keycloak realm is re-rendered with that host as a valid redirect URI.
For browser-side crypto (DSN scrubber), Chrome needs the
`unsafely-treat-insecure-origin-as-secure=http://192.168.0.42:5173` flag.

### Make targets

|                                            |                                                                 |
| ------------------------------------------ | --------------------------------------------------------------- |
| `make` / `make dev`                        | full stack (infra + JVM services + web) — `make` defaults to `dev` |
| `make demo`                                | one-shot: reset + fresh + dev stack + auto-seed (log: `/tmp/arguslog-seed.log`) |
| `make seed`                                | demo Keycloak user + org + project + synthetic events (manual)  |
| `make up` / `down`                         | infra only (compose up `--wait` / down)                         |
| `make fresh`                               | drop infra volumes + re-pull images + bring infra back up       |
| `make logs` / `ps`                         | tail / inspect infra                                            |
| `make api`                                 | `arguslog-api` foreground (`:8081`)                             |
| `make ingest`                              | `arguslog-ingest` foreground (`:8080`)                          |
| `make worker`                              | `arguslog-worker` foreground (`:8082`)                          |
| `make web`                                 | Vite dev server (`:5173`)                                       |
| `make build`                               | Gradle + Turbo full build                                       |
| `make lint` / `typecheck` / `test` / `e2e` | quality gates                                                   |
| `make clean` / `reset`                     | drop build artifacts / nuke containers + volumes + node_modules |
| `make doctor`                              | check prerequisites (OS-specific install hints on misses)       |
| `make help`                                | list all targets                                                |

## Tests

```bash
pnpm test                   # JS unit (vitest, all workspaces)
pnpm test:coverage          # same, with v8 coverage gate
pnpm e2e                    # Playwright (run pnpm e2e:install once first)
./gradlew check             # Java unit + integration (Testcontainers)
make python-test            # pytest under python-sdk/ (uv-managed venv)
```

## SDKs

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
| MCP server          | `@arguslog/mcp-server`           | `packages/mcp-server/`       |

A standalone install + quickstart index for every SDK lives in
[`docs/sdks.md`](docs/sdks.md). The Web3 add-on layers on top of any
JS-runtime SDK and decodes wallet / chain / contract / Anchor errors
from viem, ethers v6, `@solana/web3.js`, Anchor, wagmi, and
WalletConnect into searchable Arguslog issues.

## Contributing

PRs welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for setup, coding
conventions, and the test gates that have to pass on every PR.

Security vulnerability? See [SECURITY.md](SECURITY.md) — please do not file
public GitHub issues for exploitable bugs.

## License

MIT — see [LICENSE](LICENSE). Java SDK ships as Apache-2.0 under the same
copyright.
