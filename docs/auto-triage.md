# Auto-triage — Arguslog alerts → GitHub Issue → Copilot PR

When a new error event fires, Arguslog creates a GitHub Issue in your repo and assigns it
to GitHub Copilot's coding agent (`copilot-swe-agent`). Copilot picks the issue up
automatically, reads the stack trace + breadcrumbs out of the issue body, greps the repo,
and opens a *draft* PR with the smallest plausible fix. The PR shows up in your normal
review queue.

Zero workflow files. Zero `ANTHROPIC_API_KEY`. The operator's existing Copilot
subscription is the entire runtime.

## Architecture

```
┌──────────────┐  new error  ┌──────────────┐  POST /issues  ┌──────────────────┐
│ ingest       │ ──────────► │ worker rule  │ ─────────────► │ GitHub API       │
│ pipeline     │             │ engine       │                │ creates issue +  │
└──────────────┘             └──────────────┘                │ assigns Copilot  │
                                                              └────────┬─────────┘
                                                                       │ Copilot
                                                                       │ coding agent
                                                                       ▼
                                                              ┌──────────────────┐
                                                              │ Draft PR against │
                                                              │ `main` with the  │
                                                              │ proposed fix     │
                                                              └──────────────────┘
```

## Prereqs

- **GitHub Copilot subscription**: Pro+ or Enterprise with the **Copilot coding agent**
  enabled. Free / individual Pro don't include the agent.
- **The target repository**: Settings → Code & automation → Copilot → confirm the coding
  agent is on. Your default branch (usually `main`) should be the PR target.
- **A fine-grained GitHub PAT** scoped to that *one* repository with:
  - `Contents: read` (Copilot reads code to make its diff; Arguslog itself doesn't, but
    the PAT covers the agent path too)
  - `Issues: write` (Arguslog creates the issue + assigns the agent)
  - `Pull requests: read` (for the future PR-link write-back webhook)
  - Set a short expiration if your security policy requires; rotate via the dashboard's
    edit modal when it expires.

> **Why fine-grained, not classic PAT**: classic PATs are org-wide superkeys. Scoping to a
> single repo is the principle-of-least-privilege posture and keeps the blast radius of a
> leaked token to the one repo Arguslog auto-triages.

## Setup

### 1. Create the Arguslog destination

In the dashboard: **Settings → Alerts → Destinations → New**, pick kind
**„GitHub Issue (auto-triage)"**, fill in:

| Field | Value |
|---|---|
| Name | Anything memorable (e.g. „auto-triage → acme/web") |
| Owner | GitHub username / org (e.g. `acme`) |
| Repo | Repository name only (e.g. `web`) |
| Fine-grained PAT | The token from the prereqs section |
| Assignee | Leave blank for the default `copilot-swe-agent` |
| Labels | Leave blank for the default `arguslog-auto-triage` |

Or via API:

```bash
curl -X POST "$ARGUSLOG/api/v1/orgs/$ORG_ID/alert-destinations" \
  -H "Authorization: Bearer $PAT" -H 'Content-Type: application/json' \
  -d '{
    "kind": "github_issue",
    "name": "auto-triage → acme/web",
    "config": {
      "owner": "acme",
      "repo": "web",
      "token": "github_pat_…"
    }
  }'
```

### 2. Wire an alert rule

Pick a project, **Settings → Alerts → Rules → New**:

- **Condition**: „new error event" (or a tighter level filter — e.g. only `error|fatal`)
- **Action → Destination**: the destination from step 1.
- **Throttle**: a few minutes if the same error storm could fire many alerts in a row.
  Each fire creates a new GitHub Issue.

### 3. Verify

Send a synthetic event from the Connect-Project wizard (the SDK emits an
`ArguslogConnectivityProbe` exception).

Within ~30 s:
- A new GitHub Issue appears in your repo, titled `[Arguslog] error in <project>:
  <error title>`, with the markdown body containing the stack trace + recent
  breadcrumbs + a link to the Arguslog issue.

Within a few minutes (Copilot's pickup queue is GitHub-side, not Arguslog-side):
- A draft PR from `copilot-swe-agent` shows up referencing the issue with `Closes #N`.

## Pausing auto-triage

Every destination — `github_issue` included — has a generic **enabled** toggle. Flip it
off in **Settings → Alerts → Destinations** during a freeze window or noisy storm; the
encrypted PAT stays on file, the worker dispatcher just skips disabled destinations.
Flip it back on after.

## Loop guard

- `attach_ai_analysis` (v1's write-back endpoint, shipped in `a349a21`) doesn't emit
  events — it can't re-trigger the alert rule that woke the agent up.
- The PR being opened by Copilot does NOT trigger Arguslog. Arguslog ingests runtime
  errors, not git events. Net effect: there's no agent → CI → agent cycle to worry about.

## Costs

- **Arguslog side**: free. The dispatcher is a single HTTP POST per fired rule.
- **GitHub Copilot side**: billed against your existing Copilot subscription. Use a tight
  alert-rule condition (level + occurrence threshold) and a sensible throttle if your
  Copilot quota is finite.

## What's NOT in this version

- **PR-link write-back into Arguslog's `aiAnalysis` field**. Today the PR description
  references the GitHub issue, and the GitHub issue's body references the Arguslog
  issue — the chain is navigable both ways. Wiring the PR URL automatically into the
  Arguslog issue card requires the operator to set up a separate `pull_request: opened`
  GitHub webhook → Arguslog endpoint that parses the PR body for the Arguslog issue link
  and PATCH-es `attach_ai_analysis`. Future iteration.
- **GitLab equivalent**. The architecture is identical: a `gitlab_issue` destination kind
  hitting `https://gitlab.com/api/v4/projects/:id/issues` with `assignee_ids: [<duo-bot>]`.
  Add when there's demand.
- **GitHub Actions + `anthropics/claude-code-action` path**. Considered and dropped — it
  cost the operator a workflow file and an `ANTHROPIC_API_KEY` secret with no offsetting
  benefit. The Copilot path is simpler.
