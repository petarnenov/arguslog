# Slack integration — inbound (slash commands + workspace install)

> Goal: a user installs the Arguslog Slack app into their workspace through the
> dashboard, picks a default project, and can run `/arguslog issues|issue|
> resolve|release|set-project` from any channel. Workspace state is managed
> from a Settings → Integrations → Slack page.
>
> Definition of done:
>
> - Org admin clicks "Connect Slack" in dashboard → Slack OAuth consent →
>   bot lands in workspace → row appears in dashboard with default project.
> - Slash commands work end-to-end against the installed workspace.
> - Disconnect button revokes the install (deactivates the row); reinstall is
>   idempotent and clears the tombstone.
>
> Out of scope for this plan (deferred): `/arguslog ping`, MCP curated wrapper
> for workspace management, `app_uninstalled` Slack Events API handler.

## Milestone tracker

| #   | Phase | Milestone                                                                                                                                                              | Status      | Commit      |
| --- | ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- | ----------- |
| 1   | 1+2   | `V36__slack_workspaces.sql` schema + RLS + `SlackWorkspace` domain + JDBC repo (read + write) + `SlackSigningVerifier` + SecurityConfig allowlist for `/api/v1/slack/**`. | ✅ done     | `64fb2ae`   |
| 2   | 3     | `POST /api/v1/slack/commands` — `SlackController` + `SlackCommandDispatcher` (help / issues / issue / resolve / release) + `SlackBlockBuilder` + DTOs.                  | ✅ done     | `794c586`   |
| 3   | 4     | **OAuth install flow.** `SlackOAuthService` (state JWT + token exchange) + `SlackInstallController` (`GET /api/v1/orgs/{orgId}/integrations/slack/oauth/install`, `GET /api/v1/slack/oauth/callback`). Wires `upsert()`. | ✅ done     | `1c63d1f`   |
| 4   | 5     | **Dashboard REST API.** `IntegrationsSlackController` under `/api/v1/orgs/{orgId}/integrations/slack/workspaces` (`GET` list, `DELETE /{id}`, `PATCH /{id}` defaultProjectId). `SlackWorkspaceDto` excludes `install_token`. | ✅ done     | `3f237ea`   |
| 5   | 6     | **Dashboard UI.** `SlackIntegrationsPage` under `/orgs/{orgSlug}/settings/integrations/slack` — list / connect / disconnect / pick default project. Sidebar link.       | ✅ done     | `9e43bfa`   |
| 6   | 7     | **`/arguslog set-project <slug>` subcommand.** Extends dispatcher switch + help text. Calls `setDefaultProject`.                                                       | ✅ done     | `204a52f`   |

## Phase 4 — OAuth install flow (point A)

**Why first:** without it `upsert()` is dead code and every slash command
returns "workspace not connected".

**Doable units:**

- Properties: `arguslog.slack.client-id`, `arguslog.slack.client-secret`,
  `arguslog.slack.redirect-uri` (signing-secret already exists).
- `SlackOAuthService`:
  - `encodeState(orgId, userId)` — HMAC-signed JWT, 5-min TTL, nonce.
  - `decodeState(token)` — verifies sig + expiry + that userId matches the
    current authenticated principal (defence against state-leak phishing).
  - `exchangeCode(code)` — POST to `https://slack.com/api/oauth.v2.access`,
    parses `team.id`, `team.name`, `access_token` (bot token, `xoxb-…`),
    `authed_user.id`.
- `SlackInstallController`:
  - `GET /api/v1/integrations/slack/oauth/install` — JWT-protected; builds
    state, 302 → `https://slack.com/oauth/v2/authorize?...&scope=commands,chat:write,incoming-webhook&state=...`.
  - `GET /api/v1/slack/oauth/callback` — allow-listed (state is the auth);
    decodes state → `exchangeCode` → `writeRepo.upsert(...)` → 302 to
    `${dashboard-base-url}/settings/integrations/slack?installed=<team>`.
- SecurityConfig: extend the existing `permitAll` for `/api/v1/slack/**`
  to also cover `/api/v1/integrations/slack/oauth/install` is **NOT**
  needed — install endpoint is JWT-protected. Only the callback is
  allow-listed (already covered by the `/api/v1/slack/**` matcher).
- Audit log: append `slack.workspace.installed` / `slack.workspace.reinstalled`
  on upsert (audit infra already shared across the codebase).

**Tests:**

- `SlackOAuthServiceTest` — state round-trip, expired token, wrong-user
  token, bad signature.
- `SlackInstallControllerTest` — install redirect carries valid state;
  callback happy path with WireMock stub for `oauth.v2.access`; double-install
  is idempotent; missing `code` → 400; bad state → 401.

## Phase 5 — Dashboard REST API (point B)

**Why next:** UI in phase 6 will consume these endpoints.

**Doable units:**

- `IntegrationsSlackController` under `/api/v1/integrations/slack/workspaces`
  (RLS-protected via OrgContext, same pattern as `AlertDestinationController`):
  - `GET` → `slackRepo.listForOrg(currentOrgId)` → `List<SlackWorkspaceDto>`.
  - `DELETE /{id}` → `writeRepo.deactivate(id)` (404 if not in current org).
  - `PATCH /{id}` body `{ "defaultProjectId": 123 }` → `setDefaultProject(...)`.
- `SlackWorkspaceDto` — only `id`, `slackTeamId`, `slackTeamName`,
  `defaultProjectId`, `installedAt`, `installedByUserId`. **No
  `installToken`** (sensitive).
- OpenAPI regenerates automatically (no manual yaml edits — `openapi.json`
  is service-emitted).

**Tests:**

- `IntegrationsSlackControllerTest` — list with `@WithMockJwt`; cross-org
  RLS isolation (org A's JWT cannot see org B's workspaces or hit DELETE
  on them); DELETE on already-deactivated → no-op 204; PATCH with project
  belonging to a different org → 400.

## Phase 6 — Dashboard UI (point C)

**Why next:** wraps phases 4–5 in a user-visible workflow.

**Doable units:**

- Route: `/settings/integrations/slack` (org-scoped — matches the "UI scope
  mental model" memory: integrations belong in sidebar/Settings, not
  user-menu).
- `apps/web/src/pages/SlackIntegrationsPage.tsx`:
  - On mount: `GET /api/v1/integrations/slack/workspaces` → table.
  - "Connect Slack" button: `window.location.assign('/api/v1/integrations/slack/oauth/install')`.
  - Each row: team name, project picker (reuse existing `ProjectSelector` or
    inline select), "Disconnect" button with confirmation modal.
  - Read `?installed=<team>` query on mount → toast "Connected <team>".
- `apps/web/src/api/slackIntegrations.ts` — `listSlackWorkspaces`,
  `deleteSlackWorkspace`, `setSlackDefaultProject`.
- Sidebar: add "Integrations" group under Settings (single link for now;
  more integrations can join later).

**Tests:**

- `SlackIntegrationsPage.test.tsx` — list rendering, empty state, disconnect
  confirmation, project picker mutate.
- `slackIntegrations.test.ts` — API client unit tests with MSW.

## Phase 7 — `/arguslog set-project <slug>` (point D)

**Why last:** trivial dispatcher addition; doesn't block any other phase.

**Doable units:**

- Extend `SlackCommandDispatcher` switch with `set-project` case.
- Project lookup by `slug + orgId` via existing project repo (must already
  expose `findBySlugAndOrg` or equivalent — verify before starting; add if
  missing).
- On hit: `writeRepo.setDefaultProject(workspace.id, project.id)` →
  in-channel confirmation "Default project set to <slug>".
- On miss: ephemeral "Project `<slug>` not found in this org".
- Update `SlackBlockBuilder.help()` to list the new command.

**Tests:**

- `SlackCommandDispatcherTest` — set-project happy path, unknown slug,
  missing slug arg (`/arguslog set-project` with no text → usage hint).

## Architecture decisions

- **State JWT, not Redis session.** OAuth install flow is short-lived
  (seconds), so a self-contained signed token is simpler than a Redis
  round-trip. Same crypto material as PAT signing.
- **Bot scopes minimal at install.** `commands` (slash commands) +
  `chat:write` (post via `response_url` already covers most use cases —
  `chat:write` lets us push to channels later without re-consent) +
  `incoming-webhook` (lets users pick a channel for future
  worker-side broadcasts; reusing existing webhook flow).
- **Install token stored encrypted via `SecretCipher`.** Already wired in
  `JdbcSlackWorkspaceRepository`. No change needed.
- **Dashboard endpoint paths.** `/api/v1/integrations/slack/...` (plural
  "integrations" leaves room for future GitHub / Linear / etc. integrations
  under the same prefix).
- **Reinstall is upsert, not insert + tombstone-resurrect dance.** The
  `ON CONFLICT (slack_team_id)` clause already clears `deactivated_at`.
  Phase 4's callback just calls `upsert` blindly.

## Out of scope (carry-forward)

| Letter | Item                                                                                              | Why deferred                                                                            |
| ------ | ------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| E      | `/arguslog ping` subcommand                                                                       | Requires synthetic event builder + HTTP ingest client; cleanest to do after SDK stabilises. |
| ~~F~~  | ~~MCP curated wrapper for workspace management~~ — `list_slack_workspaces` / `revoke_slack_workspace` / `set_slack_default_project` shipped in curated-tools.ts; install/callback stay auto-generated only (redirect-based, not useful as MCP tools). | Done.                                                                                   |
| G      | `app_uninstalled` Slack Events API handler                                                        | Users can disconnect from dashboard; auto-deactivate on Slack-side uninstall is polish. |
