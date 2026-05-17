# Changelog

## Unreleased

### Added — browser-extension 1.0.0, Chrome MV3 best-practices sync

Bumps the sidepanel extension from a pre-release `0.1.0` to a Web Store
publishable `1.0.0` and lands the four-phase audit follow-up against Chrome's
Manifest V3 docs:

- **Permissions tightening**: dropped `'tabs'` from the manifest in favour of
  `'activeTab'` — the single tab-query call site is the textbook `activeTab`
  scenario, so the broader permission was pure attack-surface waste.
- **Service-worker lifecycle**: top-level `chrome.runtime.onInstalled` handler
  logs version transitions and runs storage migrations so on-disk blobs from
  the previous version are lifted instead of silently wiped.
- **Storage schema versioning**: new `src/shared/storage/schema-version.ts`
  helper wraps every `chrome.storage` blob in `{ __schemaVersion, data }`
  with a per-store migrations chain. Legacy bare-payload blobs from
  pre-1.0 versions are tolerated as v1 and lifted on first read; the
  PAT vault's AES-GCM envelope stays unversioned (encrypted plaintext
  hasn't changed shape).
- **Real-browser smoke test**: new Playwright suite at
  `tests/e2e/sidepanel.spec.ts` loads the unpacked build into Chromium
  and asserts the manifest stays MV3, the sidebar contains the eight
  expected items, and the deprecated "Connect" nav-link stays gone.
- **Bundle perf**: switched the eight sidepanel screens to
  `React.lazy()` so the entry chunk dropped from **231 KB to 27 KB**
  (~8.5×). Each screen streams in on first navigation; total bundle
  grew ~11 KB due to per-chunk preamble overhead but the operator-visible
  cold start is dramatically faster. A `tests/unit/bundle-size.test.ts`
  guard ratchets `sidepanel-entry < 50 KB`, `background.js < 320 KB`,
  and `total .output < 1 000 KB`.
- **i18n scaffolding**: new `public/_locales/en/messages.json` with the
  20 anchor strings (extension name + description, eight sidebar
  labels, six primary buttons, three error banners) plus a `useI18n`
  hook (`src/shared/hooks/useI18n.ts`) over `chrome.i18n.getMessage`.
  Default locale set in the manifest so unsupported UI languages fall
  back to English. Adding Bulgarian / German / Russian is now one PR
  of additional locale files plus more keys — no code refactor.
- **Web Store publish prep**: new `PRIVACY.md` covering every
  `chrome.storage` blob the extension touches; manifest `homepage_url`
  points at the hosted privacy policy. `apps/browser-extension/store-assets/`
  scaffolds the screenshot + promo + listing-copy assets the operator
  produces before submission.

This is the foundation for shipping the extension to the Chrome Web
Store. The remaining publish blockers are the operator-owned creative
deliverables (screenshots, promo tiles, listing copy) documented in
`store-assets/README.md`.

### Changed — React Connect onboarding ships the env-driven installer shape

Phase 1 of the cross-SDK onboarding rework. The React `SDK_CATALOG` entry migrated
to the same 3-file `initFiles[]` shape Vue uses: `.env.local` (DSN via
`VITE_ARGUSLOG_DSN`), `src/arguslog.ts` (a named `installArguslog()` that no-ops
when the DSN is missing — safe for local dev), and `src/main.tsx` (calls the
installer between `createRoot` and render). The Connect screen's React tab now
renders the same workflow-first 7-step flow Vue does — verification checklist that
auto-ticks on test-event success, recommended-architecture telemetry-service
example, magic-prompt that inlines the real DSN into the env block.

The Vue-specific `<VueOnboardingFlow>` component was generalised into a
slug-driven `<OnboardingFlow>` that reads everything (steps, files, checklist,
telemetry, wrap snippet) from the catalog entry. Adding the next SDK (Next.js,
Angular, RN) to the flow is now an `initFiles[]` + `extras` migration in the
catalog — no per-SDK React component required. The cross-SDK integrity test is
parametrized over every catalog entry shipping `initFiles[]`, so a React-snippet
symbol-import drift would fail CI the same way the Vue one does.

### Fixed — Vue Connect onboarding ships an env-driven installer

Resolves [`arguslog-sdks#2`](https://github.com/petarnenov/arguslog-sdks/issues/2)
(integration UX feedback). The Vue magic-prompt + `SDK_CATALOG` entry now ship a
3-file production-realistic install shape — `.env.local` (DSN via
`VITE_ARGUSLOG_DSN`), `src/arguslog.ts` (a named `installArguslog(app)` module
that no-ops when DSN is missing), and `src/main.ts` (calls the installer). The
ErrorBoundary docs + wrap snippet now use the required `:fallback` prop (the
runtime contract; `<template #fallback>` slot syntax was silently broken).
Phase A of the rework — Phase B (workflow-first Connect UI restructure) lands
separately.

### Added — per-project Git link drives the release branch dropdown

Projects gained an optional `(git_provider, git_repo)` pair (V41 migration). The
"Create release" form, when a project has a Git link configured, replaces the
manual Git ref / Git SHA text inputs with a branch dropdown fed by the provider's
public branches API. Picking a branch auto-fills the SHA — no more pasting from
`git log`.

- **Providers**: `github` (`owner/repo`) and `gitlab` (`group/project`, including
  nested groups like `group/sub/project`). Public hosts only — self-hosted
  GitLab CE / GitHub Enterprise is a separate piece of work.
- **Wire shape**: `gitProvider` + `gitRepo` on `Project`, both fields are either
  set together or both null (CHECK-constrained).
- **New endpoint**: `GET /api/v1/orgs/{orgId}/projects/{projectId}/git/branches`
  proxies unauthenticated GETs to the relevant provider (`api.github.com` /
  `gitlab.com/api/v4`) with a 60-second in-memory cache. Returns
  `[{name, sha}]` — provider-agnostic shape; GitLab's `commit.id` is normalised
  to `sha` server-side. Errors map to ProblemDetail types
  (`git-repo-missing` = 422, `git-repo-not-found` = 404,
  `git-rate-limited` = 429 with `resetAt` extension, `git-upstream` = 502).
- **API ergonomics**: `PATCH /api/v1/orgs/{orgId}/projects/{projectId}` is now a
  partial update — `name` and the Git link are independently optional. The old
  `rename_project` MCP tool stays as a thin wrapper; new callers should use the
  new `update_project` tool which can touch either field in one call.
- **Paste-friendly normalisation**: full URLs (`https://github.com/owner/repo`,
  trailing `/`, `.git` suffix) and SSH clone strings (`git@github.com:owner/repo`)
  are accepted on the wire — the server reduces them to canonical form. Mixed
  hints (e.g. `gitProvider=github` + `gitRepo=https://gitlab.com/…`) are
  rejected with 400.
- **Release modal UX**: graceful fallback — if the provider responds 4xx/5xx or
  the project has no Git link, the user gets the original free-form text inputs
  back with a "Type a branch name manually" button, so the form is never blocked
  by upstream flakiness.

### Fixed — GitHub Issue auto-triage now actually assigns Copilot

`POST /repos/{owner}/{repo}/issues` validates its `assignees` array against the
human-collaborator list and rejects bot/app identities — including
`copilot-swe-agent`, which is the handle backing GitHub Copilot's Cloud Agent —
with HTTP 422 (`"copilot-swe-agent cannot be assigned to this issue"`). The
dispatcher was emitting a single POST and silently logging the 422, leaving
issues uncreated and auto-triage non-functional.

The fix splits dispatch into two calls:

1. `POST /issues` — create the issue with title + body + labels but **no
   assignees** (this endpoint succeeds for any well-formed token).
2. `POST /issues/{n}/assignees` — assign the configured handle via the
   sub-resource, which accepts bot/app logins that the create endpoint rejects.

The flow is uniform regardless of whether the configured assignee is a human or
a bot. Step-2 failure leaves the issue created — we log a WARN noting the
assignee call failed so the operator can pick it up manually rather than
attempting a roll-back that could mask the upstream issue.

Companion UX touch: the **Assignee** field on the alert-destination create form
is now pre-populated with `copilot-swe-agent` so the common case doesn't depend
on hand-typing the handle from docs. The edit form zeros it back to blank
(blank-on-edit = "keep the stored value", consistent with secret rotation
semantics).

See `docs/auto-triage.md` for the updated architecture diagram and operator
walkthrough.

### Changed — `make demo` grants the seeded demo user platform-admin

Local-only convenience: the `demo` Make target now sets
`ARGUSLOG_PLATFORM_ADMINS=demo@arguslog.local` for the recursive `$(MAKE)`
invocation, so the auto-seeded Keycloak user (`demo@arguslog.local`) lands on a
stack that surfaces the Admin sidebar item without manual env-var wrangling.
Plain `make` / `make dev` are untouched. Override-friendly — a developer's
shell or `.env.local` wins, since `dev` sources `.env.local` after this var is
set on the recursive call.

### Added — Read · Eval · Triage · Loop workflows (mcp-server 2.4.0)

The landing slogan finally has a concrete deliverable. The MCP server now
exposes a `prompts/` capability with four canned playbooks the agent runs
end-to-end:

- **`arguslog_triage_loop`** — walks the unresolved queue one issue at a time,
  proposes an action (assign / resolve / set release), waits for the user's
  OK, applies via MCP tools. Operationalises the "Loop".
- **`arguslog_release_postmortem`** — fetches issues first-seen-in-a-given-
  release, groups by stack-frame fingerprint, writes a Markdown postmortem.
  Read-only by design.
- **`arguslog_regression_check`** — diffs two releases, surfaces issues that
  are new or spiking ≥3× current vs previous count, pairs each finding with
  git blame on the top stack frame.
- **`arguslog_investigate_issue`** — deep-dives one issue, hypothesises root
  cause from breadcrumbs + recent events, proposes a fix diff.

Discoverable via `prompts/list` (Claude Code / Cursor / Continue see them as
slash-style commands) and mirrored on the Connect → Workflows tab for every
other agent. Mutating tools are gated behind explicit user confirmation in
every workflow body — no auto-apply.

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
  `instrumentation.ts` with `onRequestError` for Next.js server; Vue `createArguslog()`
  factory via `app.use(createArguslog({...}))`; Python `install_excepthook=True` +
  `install_logging_handler=30`;
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
  - DELETE + PATCH. Ships in `@arguslog/mcp-server@2.2.0`.

### Configuration

`arguslog-api` needs these env vars for the install flow to work (otherwise
the install endpoint fail-closes to 503; existing routes are unaffected):

| Variable                   | Purpose                                                                                                                                            |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SLACK_CLIENT_ID`          | OAuth app client id (`slack.com/apps` → your app → Basic Information)                                                                              |
| `SLACK_CLIENT_SECRET`      | OAuth app client secret (same page)                                                                                                                |
| `SLACK_SIGNING_SECRET`     | HMAC key Slack uses on every slash-command POST (already required for P3 outbound)                                                                 |
| `SLACK_OAUTH_STATE_SECRET` | HMAC key for the install-flow state token. **MUST be distinct from `SLACK_SIGNING_SECRET`** — leaking one must not let an attacker forge the other |
| `SLACK_OAUTH_REDIRECT_URI` | Public URL of the callback endpoint (must match a redirect URL registered in the Slack app config)                                                 |

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
