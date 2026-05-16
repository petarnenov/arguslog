# Auto-triage — Arguslog alerts → hosted Claude agent → AI analysis

When a new error event fires, Arguslog can POST a webhook to a hosted Claude agent. The
agent fetches the issue + recent event payloads via MCP, hypothesises a root cause, and
attaches the markdown back to the issue via `attach_ai_analysis`. The dashboard renders the
analysis in a dedicated card on `IssueDetailPage`.

This loop is suggestion-only. The agent never touches status or assignee — a human still
owns the triage decision.

## Architecture

```
┌──────────────┐  new error  ┌──────────────┐  webhook POST  ┌──────────────────┐
│ ingest       │ ──────────► │ worker rule  │ ─────────────► │ Claude Managed   │
│ pipeline     │             │ engine       │                │ Agent (or equiv) │
└──────────────┘             └──────────────┘                └────────┬─────────┘
                                                                      │
                                                            MCP calls │ (mcp.arguslog.org)
                                                                      ▼
                                                              ┌──────────────┐
                                                              │ Arguslog API │
                                                              │ - get_issue  │
                                                              │ - list_issue │
                                                              │   _events    │
                                                              │ - attach_ai_ │
                                                              │   analysis   │
                                                              └──────────────┘
```

## Setup

### 1. Create an agent PAT

`User menu → Personal access tokens → New token` on the dashboard. Scope it to
`issues:read` and `issues:write` (the agent needs to read events + write the analysis
back). Note the token — shown once.

### 2. Provision a hosted Claude agent

Use Anthropic's Managed Agents (or any equivalent host that exposes an HTTPS invoke URL +
auth header). The agent needs:

- **MCP connection**: `https://mcp.arguslog.org` with `Authorization: Bearer <PAT>` from
  step 1.
- **Prompt**: the template below, verbatim.
- **Trigger**: HTTPS POST webhook. Take note of the invoke URL + the auth header the host
  expects (usually a per-agent secret).

### 3. Prompt template

```
You are an Arguslog auto-triage agent. You will receive a JSON payload describing a freshly-fired
alert: { issueId, projectId, level, occurrenceCount, url, ... }.

Your job, end-to-end:
1. Call `get_issue` with the projectId + issueId from the payload.
2. Call `list_issue_events` (page size 3) to read recent event payloads — stack frames,
   breadcrumbs, request/user contexts.
3. Write a short root-cause hypothesis (1–3 paragraphs, markdown) and a suggested next step or
   fix. Be concrete; reference file paths and frame numbers from the stack when possible. If the
   data is insufficient to form a hypothesis, say so plainly — don't invent.
4. Call `attach_ai_analysis` with your markdown body and `model: <your model id>`.

You MUST NOT change issue status or assignee. The human owns the triage decision. The analysis
you attach is a suggestion, not an action.
```

### 4. Wire the webhook in Arguslog

On the project's `Settings → Alerts → Destinations` page:

1. **New destination** → type `Webhook` → URL = your agent's invoke URL → optional
   `Authorization` header from step 2.
2. **New alert rule** → fires on `new error event` (or whichever condition matches your
   auto-triage policy) → action = the destination from step 1.

Or, programmatically (matches the API-first / MCP-first design rule):

```bash
# Create the webhook destination
curl -X POST "$ARGUSLOG/api/v1/orgs/$ORG_ID/alert-destinations" \
  -H "Authorization: Bearer $PAT" -H 'Content-Type: application/json' \
  -d '{
    "name": "Auto-triage agent",
    "kind": "webhook",
    "config": { "url": "https://agent.example/invoke", "secret": "shared-hmac-secret" }
  }'

# Wire an alert rule on the project
curl -X POST "$ARGUSLOG/api/v1/projects/$PROJECT_ID/alert-rules" \
  -H "Authorization: Bearer $PAT" -H 'Content-Type: application/json' \
  -d '{
    "name": "Auto-triage on new error",
    "condition": { "type": "new_issue", "level": "error" },
    "destinationIds": [42]
  }'
```

### 5. Verify

Trigger a synthetic event from the Connect-Project wizard (the SDK generates an
`ArguslogConnectivityProbe` event). Within a few seconds:

1. The webhook POSTs the alert payload to the agent.
2. The agent calls back via MCP.
3. The dashboard's `IssueDetailPage` shows the new AI analysis card.

## Loop guard

The alert rule fires on **new error event**. `attach_ai_analysis` writes to the issue but
does NOT create an event — it cannot re-trigger the same rule, so the agent can't infinite-
loop on its own output. A unit test (`IssueTriageServiceTest`) asserts this contract.

## Costs and rate-limiting

Each agent invocation is an LLM token bill. For v1, cost guard is the operator's
responsibility — set a sensible per-rule throttle on the destination, or use the agent host's
own budget controls. A per-project cap inside Arguslog is a future addition.

## What the agent canNOT do (deliberately)

- Change issue status (resolve / ignore / reopen). Suggestion-only.
- Change assignee. Same reason.
- Comment on Slack / Linear / GitHub. Single canonical place for the analysis: the
  `IssueDetailPage` card.

If a future workflow wants the agent to write back to Slack threads or open external
tickets, that's a separate MCP tool plus webhook-config affordance. Out of scope for v1.
