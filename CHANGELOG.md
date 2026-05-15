# Changelog

## Unreleased

### Improved — full default integrations + framework wrap in every agent prompt

Step 2 of the magic prompt (the "install the SDK and wire init()" block) now
emits per-stack full templates with the recommended default integrations
already wired:
- Browser-family SDKs (javascript, react, vue, angular, nextjs client, web3):
  `integrations: ['globalHandlers', 'autoBreadcrumbs']`.
- Server SDKs (node, nextjs server, instrumentation.ts): `['processHandlers', 'http']`.
- React Native: `['globalHandlers']` (no DOM / no breadcrumbs).
- Plus framework wraps where the SDK exports one: `<ArguslogErrorBoundary>` for
  React, Vue, Next.js client, and React Native; `provideArguslog()` for Angular;
  `instrumentation.ts` with `onRequestError` for Next.js server; Vue `arguslogPlugin`
  via `app.use(...)`; Python `install_excepthook=True` + `install_logging_handler=30`;
  Spring Boot autoconfig YAML. The agent picks the section matching its detected
  stack and pastes it verbatim — no more stripped-down `init({ dsn })`.

### Fixed — MCP config schemas verified against current docs (May 2026)

Direct WebFetch on each tool's MCP docs surfaced four schema bugs in the V3
magic-prompt rollout. Each was the same class of mistake as the Copilot CLI
migration that bit us — guessing the schema from memory instead of reading the
docs. Fixed:

- **Codex CLI**: switched from invalid Claude-Code-style `.mcp.json` to the
  correct `~/.codex/config.toml` with `[mcp_servers.<name>]` TOML blocks. The
  old JSON form was silently rejected by Codex.
- **Windsurf**: the URL field is `serverUrl`, not `url` (per Codeium docs).
- **Continue**: migrated the prompt from the deprecated
  `experimental.modelContextProtocolServers` JSON array in
  `~/.continue/config.json` to the current `.continue/mcpServers/<name>.yaml`
  workspace files (Continue 1.0+ schema).
- **Claude Code + Copilot CLI**: added explicit `"type": "http"` for
  spec-compliance and forward-compatibility.
- **Aider**: dropped from the magic-prompt list — Aider is not an MCP client;
  the community `aider-mcp-server` project runs in the opposite direction
  (turns Aider into a server for Claude/Cursor to consume). A user pasting our
  Aider prompt would have silently no-op'd.

### Added — 3-second install for AI coding agents

The Connect page on the dashboard now ships a paste-ready magic prompt for
seven AI coding agents. Open the page, copy one prompt, paste it into your
agent, and it detects your stack, installs the matching `@arguslog/sdk-*`
at the pinned catalog version, wires `init({ dsn })`, and registers the
Arguslog MCP server in the agent's own config file. No manual `generate
DSN` / `generate PAT` round-trip — both are auto-provisioned on first
visit and inlined into the prompt at the exact key the agent reads.

- **Auto-provisioning**: first visit to Connect for a new project silently
  mints `Connect quickstart — <project>` PAT and an active DSN. Return
  visits surface a Rotate CTA (plaintext is one-shot; the old PAT remains
  valid until the user revokes it from `/me/tokens` or via the
  `delete_me_tokens` MCP tool).
- **Supported agents**: Claude Code · Cursor · Codex · GitHub Copilot Chat ·
  **Windsurf** · **Continue** · **Aider**. Each prompt writes to the agent's
  canonical config file (`.mcp.json` / `.cursor/mcp.json` / `.vscode/mcp.json`
  / `~/.codeium/windsurf/mcp_config.json` / `~/.continue/config.json` /
  `~/.aider.conf.yml`). Aider is stdio-only — PAT travels via
  `env.ARGUSLOG_PAT` to the locally-spawned `npx @arguslog/mcp-server`.
- **Prompt resilience**: the agent is explicitly told not to assume a git
  repo and not to file "replace the PAT/DSN" as a manual TODO. An
  escape-hatch paragraph documents how the user (or the agent itself) can
  rotate credentials later via the dashboard or MCP tools.
- **Landing**: new "3-second install" section under the hero promotes the
  flow with a 3-step visual and the full supported-agents list.

### Added — Slack workspace integration (inbound)

Closes the triage loop in chat. Until now Slack was a one-way alert destination
(alert webhooks shipped in P3); now operators can act on issues without leaving
the channel.

- **Slash commands** (`/arguslog …`):
  - `issues` — top 10 unresolved in the workspace's default project
  - `issue <id>` — full detail card (level, occurrences, first/last seen)
  - `resolve <id>` — mark resolved + broadcast in-channel for team visibility
  - `release <version>` — issues first seen in this release (regression signal)
  - `set-project <slug>` — switch the workspace's default project
  - `help` — command card
- **OAuth install flow** at `GET /api/v1/orgs/{orgId}/integrations/slack/oauth/install`
  redirects through Slack consent and back to a signed-state-protected
  `GET /api/v1/slack/oauth/callback` which upserts the workspace install.
- **Dashboard UI** at `/orgs/{orgSlug}/settings/integrations/slack` — list
  installs, pick a default project, disconnect. Install token never leaves
  the server (excluded from every response shape).
- **MCP tools**: `list_slack_workspaces`, `revoke_slack_workspace`,
  `set_slack_default_project` (curated) + auto-generated `slack_commands`,
  `slack_install_install`, `slack_install_callback`, `integrations_slack` GET
  + DELETE + PATCH. Ships in `@arguslog/mcp-server@2.2.0`.

### Configuration

`arguslog-api` needs these env vars for the install flow to work (otherwise
the install endpoint fail-closes to 503; existing routes are unaffected):

| Variable | Purpose |
| --- | --- |
| `SLACK_CLIENT_ID` | OAuth app client id (`slack.com/apps` → your app → Basic Information) |
| `SLACK_CLIENT_SECRET` | OAuth app client secret (same page) |
| `SLACK_SIGNING_SECRET` | HMAC key Slack uses on every slash-command POST (already required for P3 outbound) |
| `SLACK_OAUTH_STATE_SECRET` | HMAC key for the install-flow state token. **MUST be distinct from `SLACK_SIGNING_SECRET`** — leaking one must not let an attacker forge the other |
| `SLACK_OAUTH_REDIRECT_URI` | Public URL of the callback endpoint (must match a redirect URL registered in the Slack app config) |

### Migrations

- V36: `slack_workspaces` table with RLS — one row per Slack-team install,
  install token AES-GCM encrypted via the existing `SecretCipher`.

## 2.0.0 — Open-source release

The SaaS-only repository becomes a self-hostable OSS project. The hosted
instance at arguslog.org continues to run; others can now run the same
code on their own infrastructure.

### Breaking

- Removed all payment integrations: Stripe, NOWPayments, Lemon Squeezy,
  crypto checkout, customer portal, `plan_purchases` table. Every related
  controller, service, repository, and database table is deleted.
- Renamed the four user tiers: `free`→`regular`, `starter`→`silver`,
  `pro`→`gold`, `business`→`platinum`. `enterprise` folds into `platinum`
  on backfill. The wire string in `users.tier` carries the new spelling
  exclusively after V32.
- `GET /api/v1/me` now returns `{tier, tierExpiresAt, tierReason}` instead
  of `{plan, planRenewsAt, paymentGraceUntil, bonusUntil, bonusReason}`.
  Dashboard + SDKs that consume `/me` need the 2.0.0 line.
- `POST /api/v1/admin/orgs/{orgId}/grant` (per-org grant) removed. Use
  `POST /api/v1/admin/users/{userId}/grant` — the granted tier covers
  every org the user owns automatically.
- MCP server: dropped `list_billing_plans`, `grant_bonus_plan`,
  `get_org_usage`. Added `grant_user_tier` with `months=0` for permanent
  grants.

### Added

- `ARGUSLOG_DEFAULT_TIER` env var — controls the tier new signups land on.
  Defaults to `regular` (matches the hosted instance behavior); self-hosters
  who want everyone uncapped can set it to `platinum`.
- `TierExpiryJob` worker cron (daily 04:00 UTC, configurable via
  `arguslog.tier.expiry-cron`) that downgrades users whose `tier_expires_at`
  has elapsed back to `regular`.
- `SELF_HOSTING.md` operational runbook.
- `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`.

### Migrations

- V28: additive — adds `regular / silver / gold / platinum` to the
  `org_plan` enum without removing legacy values.
- V29: backfills `users.plan` from legacy names to color-themed names.
- V30: renames `users.plan` → `users.tier`, renames `bonus_*` columns to
  `tier_*`, drops `plan_renews_at`, `billing_interval`,
  `stripe_customer_id`, `payment_grace_until`.
- V31: drops `plan_purchases`, `stripe_events`, `crypto_invoices`,
  `crypto_events`, `renewal_reminders_sent` tables. Operator should
  `pg_dump` these via `scripts/archive-billing-tables.sh` before merging.
- V32: replaces the `org_plan` enum with `org_tier`
  (`regular / silver / gold / platinum` only).

### Removed

- Public mirror sync infrastructure (`scripts/sync-public-mirror.sh`,
  `scripts/public-mirror/`, `.github/workflows/sync-public-mirror.yml`).
  The single `petarnenov/arguslog` repo is now the canonical OSS home.
