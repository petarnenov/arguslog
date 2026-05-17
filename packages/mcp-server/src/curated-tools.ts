/**
 * Hand-crafted MCP tools for the most-used Arguslog operations. Each entry overrides the
 * auto-generated one of the same name from {@code generated/openapi-tools.ts} — the runtime
 * dispatcher prefers curated entries when both exist.
 *
 * <p>Curated tools have:
 *
 * <ul>
 *   <li>An LLM-friendly description that includes <em>when</em> to use the tool, not just what
 *       it does — generic OpenAPI summaries are too thin for an agent to plan with.</li>
 *   <li>Examples in the description showing typical arg shapes — anchors the LLM's first call.</li>
 * </ul>
 *
 * <p>Adding more curated entries is the cheapest way to improve LLM accuracy on a specific
 * workflow — they share the same dispatcher path as auto-generated tools, just better docs.
 */
import type { OpenApiTool } from './generated/openapi-tools.js';

export const CURATED_TOOLS: Record<string, OpenApiTool> = {
  list_my_orgs: {
    name: 'list_my_orgs',
    description: `List the organizations the authenticated user is a member of.

Always start here. Most other tools need an \`orgId\` from this list. Returns one row per org with
\`id\`, \`slug\`, \`name\`, \`plan\`, and \`createdAt\`. The \`plan\` field is one of: free,
starter, pro, business, enterprise.

Method: GET /api/v1/orgs

No arguments. Example: call this tool first to discover the user's orgs, pick the right one by
\`name\` or \`slug\`, then pass its \`id\` to other tools.`,
    method: 'GET',
    path: '/api/v1/orgs',
    pathParams: [],
    queryParams: [],
    hasBody: false,
  },

  list_projects: {
    name: 'list_projects',
    description: `List projects belonging to an organization.

Returns rows with \`id\`, \`slug\`, \`name\`, \`platform\`, \`createdAt\`. Archived projects
are excluded by default. Use the \`id\` from this list as \`projectId\` for issues / events /
release tools.

Method: GET /api/v1/orgs/{orgId}/projects

Required: \`orgId\` (number). Example: \`{ "orgId": 42 }\`.`,
    method: 'GET',
    path: '/api/v1/orgs/{orgId}/projects',
    pathParams: [{ name: 'orgId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: false,
  },

  list_issues: {
    name: 'list_issues',
    description: `List issues for a project, most-recent first.

This is the agent's main lookup tool when the user says "what broke today" or "show me the
top errors". \`status=unresolved\` (the default) is the live error wall; pass
\`status=resolved\` or \`status=ignored\` to audit silenced groups. Use \`q\` to search
across title + culprit, and \`assignee\` to narrow to a user (UUID), the caller themself
("me"), or the unassigned bucket ("none"). Pagination uses \`cursor\` from the previous
page's response.

Method: GET /api/v1/projects/{projectId}/issues

Required: \`projectId\` (number).
Optional: \`status\` (\`unresolved\`, \`resolved\`, \`ignored\`), \`level\`
(\`fatal\`, \`error\`, \`warning\`, \`info\`, \`debug\`), \`q\` (free-text substring on
title + culprit), \`assignee\` (UUID, \`me\`, \`none\`, or omitted), \`cursor\`, \`limit\`
(default 50, max 200).

Example: \`{ "projectId": 7, "status": "unresolved", "level": "error", "assignee": "me", "limit": 25 }\``,
    method: 'GET',
    path: '/api/v1/projects/{projectId}/issues',
    pathParams: [{ name: 'projectId', required: true, type: 'integer' }],
    queryParams: [
      { name: 'status', required: false, type: 'string' },
      { name: 'level', required: false, type: 'string' },
      { name: 'q', required: false, type: 'string' },
      { name: 'assignee', required: false, type: 'string' },
      { name: 'cursor', required: false, type: 'string' },
      { name: 'limit', required: false, type: 'integer' },
    ],
    hasBody: false,
  },

  triage_issue: {
    name: 'triage_issue',
    description: `Change an issue's triage status: resolve it (fixed), ignore it (don't bother
me), or reopen it (unresolved again). When an event arrives on a previously-resolved issue,
the worker auto-flips it back to unresolved — this is the "regression" signal.

Method: PATCH /api/v1/projects/{projectId}/issues/{issueId}

Required: \`projectId\`, \`issueId\`, \`body.status\` (one of: \`unresolved\`, \`resolved\`,
\`ignored\`).

Example: \`{ "projectId": 7, "issueId": 123, "body": { "status": "resolved" } }\``,
    method: 'PATCH',
    path: '/api/v1/projects/{projectId}/issues/{issueId}',
    pathParams: [
      { name: 'projectId', required: true, type: 'integer' },
      { name: 'issueId', required: true, type: 'integer' },
    ],
    queryParams: [],
    hasBody: true,
  },

  attach_ai_analysis: {
    name: 'attach_ai_analysis',
    description: `Write an auto-triage agent's root-cause hypothesis + suggested fix back onto an
issue. The body is opaque markdown — it shows up in a dedicated "AI analysis" card on
\`IssueDetailPage\`. Use this AFTER you've fetched the issue (\`get_issue\`) and at least one
recent event (\`list_issue_events\`) and formed a concrete hypothesis. Do NOT use this to change
status or assignee — that's a human's call; this endpoint is suggestion-only and explicitly
free of any event-emit side effects, so it cannot re-trigger the alert rule that woke the
agent up.

Method: PATCH /api/v1/projects/{projectId}/issues/{issueId}/ai-analysis

Required: \`projectId\`, \`issueId\`, \`body.analysis\` (markdown, max 32 KB), \`body.model\`
(your model id — e.g. \`claude-opus-4-7\`).

Example: \`{ "projectId": 7, "issueId": 123, "body": { "analysis": "**Likely cause**: NPE at\\n\\
\`render\` (app.js:42) when \`user.profile\` is undefined. Add a null-check or default.",
"model": "claude-opus-4-7" } }\``,
    method: 'PATCH',
    path: '/api/v1/projects/{projectId}/issues/{issueId}/ai-analysis',
    pathParams: [
      { name: 'projectId', required: true, type: 'integer' },
      { name: 'issueId', required: true, type: 'integer' },
    ],
    queryParams: [],
    hasBody: true,
  },

  assign_issue: {
    name: 'assign_issue',
    description: `Assign an issue to an org member, or pass \`userId: null\` to unassign. The
assignee MUST already be a member of the issue's org — otherwise the API rejects with 400.

Method: PATCH /api/v1/projects/{projectId}/issues/{issueId}/assignee

Required: \`projectId\`, \`issueId\`, \`body.userId\` (UUID string, or null to unassign).

Example: \`{ "projectId": 7, "issueId": 123, "body": { "userId": "550e8400-e29b-41d4-a716-446655440000" } }\``,
    method: 'PATCH',
    path: '/api/v1/projects/{projectId}/issues/{issueId}/assignee',
    pathParams: [
      { name: 'projectId', required: true, type: 'integer' },
      { name: 'issueId', required: true, type: 'integer' },
    ],
    queryParams: [],
    hasBody: true,
  },

  get_issue: {
    name: 'get_issue',
    description: `Fetch full details of a single issue, including its first / last seen timestamps,
total occurrences, fingerprint, and current status.

Method: GET /api/v1/projects/{projectId}/issues/{issueId}

Required: \`projectId\` (number), \`issueId\` (number). Example:
\`{ "projectId": 7, "issueId": 123 }\``,
    method: 'GET',
    path: '/api/v1/projects/{projectId}/issues/{issueId}',
    pathParams: [
      { name: 'projectId', required: true, type: 'integer' },
      { name: 'issueId', required: true, type: 'integer' },
    ],
    queryParams: [],
    hasBody: false,
  },

  list_issue_events: {
    name: 'list_issue_events',
    description: `List recent events for an issue, ordered by time descending. Each event has the
full payload (stack trace, breadcrumbs, contexts, request, web3 fields if present).

Use this after \`get_issue\` to see actual exception details — the issue row only
has aggregate stats, not the per-occurrence payloads.

Method: GET /api/v1/projects/{projectId}/issues/{issueId}/events

Required: \`projectId\`, \`issueId\`.
Optional: \`afterId\`, \`limit\` (default 50, max 200).`,
    method: 'GET',
    path: '/api/v1/projects/{projectId}/issues/{issueId}/events',
    pathParams: [
      { name: 'projectId', required: true, type: 'integer' },
      { name: 'issueId', required: true, type: 'integer' },
    ],
    queryParams: [
      { name: 'afterId', required: false, type: 'integer' },
      { name: 'limit', required: false, type: 'integer' },
    ],
    hasBody: false,
  },

  create_project: {
    name: 'create_project',
    description: `Create a new project under an org. The response carries both the project metadata
AND the auto-generated first DSN inline (full \`arguslog://…\` string, visible exactly once —
SDK config goes here). No follow-up call needed; \`list_dsns\` returns metadata only.

Method: POST /api/v1/orgs/{orgId}/projects

Required: \`orgId\`, \`body.name\` (2-100 chars), \`body.platform\` (one of: javascript, react,
vue, angular, nextjs, react-native, node, java-spring, python).

Optional Git link: \`body.gitProvider\` (\`"github"\` or \`"gitlab"\`) + \`body.gitRepo\`
(canonical \`owner/repo\` for GitHub, or \`group/project\` / \`group/sub/project\` for GitLab).
When set, the "Create release" form populates a branch dropdown and auto-fills Git SHA from
the chosen branch. Public repos only — self-hosted / private support is a separate feature.
The api also accepts paste shapes (full URLs, SSH clone strings, \`.git\` suffix) — when the
\`gitRepo\` field is a URL, \`gitProvider\` is auto-detected from the host (and validated to
match the hint if both are supplied).

Example: \`{ "orgId": 42, "body": { "name": "Marketing Web", "platform": "react" } }\`
Example with GitHub link: \`{ "orgId": 42, "body": { "name": "Web", "platform": "react", "gitProvider": "github", "gitRepo": "acme/web" } }\`
Example with GitLab nested group: \`{ "orgId": 42, "body": { "name": "API", "platform": "java-spring", "gitProvider": "gitlab", "gitRepo": "acme/backend/api" } }\`
Response shape: \`{ project: {...}, dsn: { dsn: "arguslog://...", dsnPublic, ... } }\``,
    method: 'POST',
    path: '/api/v1/orgs/{orgId}/projects',
    pathParams: [{ name: 'orgId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: true,
  },

  rename_org: {
    name: 'rename_org',
    description: `Rename an organization's display name. Slug/URL is preserved so existing links and
PATs remain valid. Caller must be the org owner.

Method: PATCH /api/v1/orgs/{orgId}

Required: \`orgId\`, \`body.name\` (2-100 chars after trimming).

Example: \`{ "orgId": 42, "body": { "name": "Acme Renamed" } }\``,
    method: 'PATCH',
    path: '/api/v1/orgs/{orgId}',
    pathParams: [{ name: 'orgId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: true,
  },

  rename_project: {
    name: 'rename_project',
    description: `Rename a project's display name. Slug/DSN/URL is preserved so existing SDK config
keeps working. Caller must be owner or admin of the org.

Thin wrapper around \`update_project\` — prefer \`update_project\` if you also want to set the
Git repo link in the same call.

Method: PATCH /api/v1/orgs/{orgId}/projects/{projectId}

Required: \`orgId\`, \`projectId\`, \`body.name\` (2-100 chars after trimming).

Example: \`{ "orgId": 42, "projectId": 7, "body": { "name": "Marketing Web (v2)" } }\``,
    method: 'PATCH',
    path: '/api/v1/orgs/{orgId}/projects/{projectId}',
    pathParams: [
      { name: 'orgId', required: true, type: 'integer' },
      { name: 'projectId', required: true, type: 'integer' },
    ],
    queryParams: [],
    hasBody: true,
  },

  update_project: {
    name: 'update_project',
    description: `Partial update of a project: change the display name and/or the Git repo link.
Each field is optional — omit (or pass null) to leave unchanged. Slug/DSN/URL is preserved so
existing SDK config keeps working. Caller must be owner or admin of the org.

Git link: pass \`gitProvider\` (\`"github"\` | \`"gitlab"\`) + \`gitRepo\` together to set or
update. To clear, pass both as empty strings (\`""\`). \`gitRepo\` accepts canonical
\`owner/repo\` (GitHub) / \`group/project\` or nested \`group/sub/project\` (GitLab), plus
common paste shapes (full URLs, SSH clone strings, \`.git\` suffix); the api normalizes
before storing. Public repos only.

Method: PATCH /api/v1/orgs/{orgId}/projects/{projectId}

Required: \`orgId\`, \`projectId\`. At least one of \`body.name\` / \`body.gitProvider\`+\`body.gitRepo\`.

Example (rename only): \`{ "orgId": 42, "projectId": 7, "body": { "name": "Web (v2)" } }\`
Example (link GitHub): \`{ "orgId": 42, "projectId": 7, "body": { "gitProvider": "github", "gitRepo": "acme/web" } }\`
Example (link GitLab): \`{ "orgId": 42, "projectId": 7, "body": { "gitProvider": "gitlab", "gitRepo": "acme/backend/api" } }\`
Example (clear link): \`{ "orgId": 42, "projectId": 7, "body": { "gitProvider": "", "gitRepo": "" } }\``,
    method: 'PATCH',
    path: '/api/v1/orgs/{orgId}/projects/{projectId}',
    pathParams: [
      { name: 'orgId', required: true, type: 'integer' },
      { name: 'projectId', required: true, type: 'integer' },
    ],
    queryParams: [],
    hasBody: true,
  },

  send_test_event: {
    name: 'send_test_event',
    description: `Send a synthetic event through ingest to verify the project's wire path
end-to-end. The MCP server looks up the project's first active DSN via the api, then POSTs
a realistic exception payload (type=ArguslogConnectivityProbe, tag synthetic=true) to the
ingest endpoint exactly as a real SDK would. Returns the event id + dsn public key + ingest
URL used.

This is the fastest way to confirm "is project X's ingest accepting traffic?" — no SDK
install needed, no curl recipe to copy.

Method: POST /internal/mcp/send_test_event (handled by the MCP server itself, not the api)

Required: \`projectId\`. Optional: \`body.message\` (custom event value), \`body.level\`
(\`fatal\`, \`error\`, \`warning\`, \`info\`, \`debug\`; default \`error\`).

Example: \`{ "projectId": 42 }\` — minimal probe.
Example: \`{ "projectId": 42, "body": { "level": "warning", "message": "agent smoke" } }\``,
    method: 'POST',
    path: '/internal/mcp/send_test_event',
    pathParams: [],
    queryParams: [{ name: 'projectId', required: true, type: 'integer' }],
    hasBody: true,
  },

  create_release: {
    name: 'create_release',
    description: `Register a release for a project. Source maps uploaded later via the artifact
endpoints attach to this release so symbolication can resolve minified frames back to original
files.

Method: POST /api/v1/projects/{projectId}/releases

Required: \`projectId\`, \`body.version\` (semver or commit sha). Optional: \`body.environment\`
(\`production\`, \`staging\`, …), \`body.commit\`.

Example: \`{ "projectId": 7, "body": { "version": "1.4.2", "environment": "production",
"commit": "abc123" } }\``,
    method: 'POST',
    path: '/api/v1/projects/{projectId}/releases',
    pathParams: [{ name: 'projectId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: true,
  },

  list_alert_rules: {
    name: 'list_alert_rules',
    description: `List alert rules for a project. Each rule has a name, enabled flag, level filter
(\`fatal | error | warning | info | debug\`), tag filters, throttle window, and the destinations
it fires to.

Method: GET /api/v1/projects/{projectId}/alert-rules`,
    method: 'GET',
    path: '/api/v1/projects/{projectId}/alert-rules',
    pathParams: [{ name: 'projectId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: false,
  },

  list_alert_destinations: {
    name: 'list_alert_destinations',
    description: `List the alert destinations configured on an org (Telegram, Slack, email,
generic webhook). Destination IDs are referenced from alert rules.

Method: GET /api/v1/orgs/{orgId}/alert-destinations`,
    method: 'GET',
    path: '/api/v1/orgs/{orgId}/alert-destinations',
    pathParams: [{ name: 'orgId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: false,
  },

  create_alert_rule: {
    name: 'create_alert_rule',
    description: `Create an alert rule on a project. All condition clauses are AND-ed; an empty
\`conditions\` object means "always match". Levels accept any subset of
\`fatal | error | warning | info | debug\`; \`firstSeenWindow\` is an ISO-8601 duration like
\`PT5M\` / \`PT2H\` / \`P1D\`; \`occurrenceThreshold\` only fires after N occurrences;
\`tag.{key,in}\` matches an SDK-supplied tag value against a non-empty list.

Actions carry at least one destinationId; cap is 8 per rule. \`throttleSeconds\` is clamped
server-side to [30, 86400]. \`enabled\` defaults to true.

Example body:
\`\`\`json
{
  "name": "production fatals",
  "conditions": {
    "level": { "in": ["fatal", "error"] },
    "firstSeenWindow": "PT5M",
    "tag": { "key": "env", "in": ["production"] }
  },
  "actions": { "destinationIds": [10, 11] },
  "throttleSeconds": 600
}
\`\`\`

Method: POST /api/v1/projects/{projectId}/alert-rules`,
    method: 'POST',
    path: '/api/v1/projects/{projectId}/alert-rules',
    pathParams: [{ name: 'projectId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: true,
  },

  update_alert_rule: {
    name: 'update_alert_rule',
    description: `Replace an alert rule. Full-PUT semantics — the entire body overwrites the
existing row, so fetch via \`get_alert_rule\` first and merge if you only want to change a
subset of fields. Same conditions / actions shape as create_alert_rule.

Method: PUT /api/v1/projects/{projectId}/alert-rules/{id}`,
    method: 'PUT',
    path: '/api/v1/projects/{projectId}/alert-rules/{id}',
    pathParams: [
      { name: 'projectId', required: true, type: 'integer' },
      { name: 'id', required: true, type: 'integer' },
    ],
    queryParams: [],
    hasBody: true,
  },

  list_members: {
    name: 'list_members',
    description: `List members of an org with their roles (owner / admin / member) and join dates.

Method: GET /api/v1/orgs/{orgId}/members`,
    method: 'GET',
    path: '/api/v1/orgs/{orgId}/members',
    pathParams: [{ name: 'orgId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: false,
  },

  invite_member: {
    name: 'invite_member',
    description: `Invite a new member to an org. The server emails the invitee a magic link and
also inserts a pending membership row. Required scope on the PAT: \`orgs:write\`.

Method: POST /api/v1/orgs/{orgId}/members

Required: \`orgId\`, \`body.email\`, \`body.role\` (\`admin\` | \`member\`).
Example: \`{ "orgId": 42, "body": { "email": "alice@example.com", "role": "member" } }\``,
    method: 'POST',
    path: '/api/v1/orgs/{orgId}/members',
    pathParams: [{ name: 'orgId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: true,
  },

  list_dsns: {
    name: 'list_dsns',
    description: `List active DSN keys for a project. The DSN is the SDK ingest credential —
SDKs authenticate to ingest with this. Returns the public part of the DSN; the secret half
is shown only at creation time.

Method: GET /api/v1/projects/{projectId}/keys`,
    method: 'GET',
    path: '/api/v1/projects/{projectId}/keys',
    pathParams: [{ name: 'projectId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: false,
  },

  grant_user_tier: {
    name: 'grant_user_tier',
    description: `[Platform admin only] Elevate a user's tier (silver / gold / platinum) for a
fixed window or permanently. The granted tier covers every org that user owns automatically
(per-user billing model). Writes \`users.tier\` + \`tier_expires_at\` + grant metadata, and
appends an audit-log entry with target_type=user.

Required: \`userId\` (UUID from list_admin_users), \`body.tier\` (\`silver\` | \`gold\` |
\`platinum\`), \`body.months\` (0 = permanent, or 1 / 3 / 6 / 12), \`body.reason\` (free text).

Method: POST /api/v1/admin/users/{userId}/grant

Example: \`{ "userId": "11111111-1111-1111-1111-111111111111", "body": { "tier": "gold",
"months": 0, "reason": "Core contributor" } }\``,
    method: 'POST',
    path: '/api/v1/admin/users/{userId}/grant',
    pathParams: [{ name: 'userId', required: true, type: 'string' }],
    queryParams: [],
    hasBody: true,
  },

  get_me: {
    name: 'get_me',
    description: `Get the authenticated user's identity + tier. Returns \`userId\`, \`email\`,
\`displayName\`, \`isPlatformAdmin\`, \`tier\` (regular / silver / gold / platinum), and the
admin-grant fields \`tierExpiresAt\`, \`tierReason\` when an active grant is in effect.

Use to answer "what tier am I on" or "when does my grant expire". Post-OSS-conversion,
billing identity lives on the user, not on individual orgs, and tier elevation is admin-
granted (no payment flow).

Method: GET /api/v1/me`,
    method: 'GET',
    path: '/api/v1/me',
    pathParams: [],
    queryParams: [],
    hasBody: false,
  },

  list_slack_workspaces: {
    name: 'list_slack_workspaces',
    description: `List Slack workspaces installed against an org. Each row carries the team id,
display name, default project id (nullable — workspaces without a default reject
\`/arguslog issues|resolve|release\` slash commands), install timestamp, and an \`active\`
flag (false = uninstalled but kept for audit).

The bot install token is intentionally NOT in the response — it's server-side only and
never leaves the API.

Method: GET /api/v1/orgs/{orgId}/integrations/slack/workspaces

Required: \`orgId\` (number from list_my_orgs). Example: \`{ "orgId": 42 }\``,
    method: 'GET',
    path: '/api/v1/orgs/{orgId}/integrations/slack/workspaces',
    pathParams: [{ name: 'orgId', required: true, type: 'integer' }],
    queryParams: [],
    hasBody: false,
  },

  revoke_slack_workspace: {
    name: 'revoke_slack_workspace',
    description: `Disconnect a Slack workspace install. Marks the row as deactivated (kept for
audit); slash commands from this team will no longer route. Idempotent — calling on an
already-deactivated workspace returns 204 the same way. Reinstalling from the dashboard
clears the tombstone.

Method: DELETE /api/v1/orgs/{orgId}/integrations/slack/workspaces/{id}

Required: \`orgId\`, \`id\` (workspace id from list_slack_workspaces).
Example: \`{ "orgId": 42, "id": 7 }\``,
    method: 'DELETE',
    path: '/api/v1/orgs/{orgId}/integrations/slack/workspaces/{id}',
    pathParams: [
      { name: 'orgId', required: true, type: 'integer' },
      { name: 'id', required: true, type: 'integer' },
    ],
    queryParams: [],
    hasBody: false,
  },

  set_slack_default_project: {
    name: 'set_slack_default_project',
    description: `Set or clear the default project for a Slack workspace install. The default
project is what \`/arguslog issues\`, \`/arguslog resolve <id>\`, and \`/arguslog release\`
operate against in the channel. The project MUST belong to the same org as the workspace —
otherwise the API rejects with 400.

Pass \`defaultProjectId: null\` to clear the default (slash commands will then reject with
"no default project set").

Method: PATCH /api/v1/orgs/{orgId}/integrations/slack/workspaces/{id}

Required: \`orgId\`, \`id\`, \`body.defaultProjectId\` (number or null).
Example: \`{ "orgId": 42, "id": 7, "body": { "defaultProjectId": 101 } }\``,
    method: 'PATCH',
    path: '/api/v1/orgs/{orgId}/integrations/slack/workspaces/{id}',
    pathParams: [
      { name: 'orgId', required: true, type: 'integer' },
      { name: 'id', required: true, type: 'integer' },
    ],
    queryParams: [],
    hasBody: true,
  },
};
