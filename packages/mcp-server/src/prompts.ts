/**
 * Arguslog MCP "prompts" capability — the canned workflows behind the landing-page slogan
 * "Read. Eval. Triage. Loop.". Each entry is a paste-ready playbook the agent runs against
 * the project, calling MCP tools (`list_issues`, `get_issue`, `triage_issue`, …) along the
 * way. The dashboard's Connect → Workflows tab mirrors these bodies verbatim so users on
 * agents that don't yet support MCP `prompts/list` can still copy-paste.
 *
 * The bodies carry NO secrets — the MCP server already authenticates every tool call with
 * the PAT from the request's Authorization header. Workflows reference projects by id, so
 * the same workflow definition is reusable across every org.
 */
import type { GetPromptResult, Prompt } from '@modelcontextprotocol/sdk/types.js';

interface WorkflowArg {
  name: string;
  description: string;
  required?: boolean;
}

interface WorkflowDef {
  name: string;
  title: string;
  description: string;
  arguments: WorkflowArg[];
  body: (args: Record<string, string | undefined>) => string;
}

function requireArg(
  args: Record<string, string | undefined>,
  name: string,
  workflow: string,
): string {
  const v = args[name];
  if (v === undefined || v === null || v === '') {
    throw new Error(
      `Workflow "${workflow}" requires argument "${name}". Pass it via prompts/get arguments.`,
    );
  }
  return v;
}

export const WORKFLOWS: WorkflowDef[] = [
  {
    name: 'arguslog_triage_loop',
    title: 'Triage loop',
    description:
      'Walk the unresolved issue queue one item at a time. For each issue, propose an action, wait for the user, apply via MCP tools. Operationalises the "Loop" half of the slogan.',
    arguments: [
      {
        name: 'projectId',
        description: 'Numeric project id from /me or /list_projects.',
        required: true,
      },
      { name: 'batchSize', description: 'How many issues to walk per batch. Default 10.' },
    ],
    body: (args) => {
      const projectId = requireArg(args, 'projectId', 'arguslog_triage_loop');
      const batchSize = args.batchSize ?? '10';
      return `You are running the Arguslog triage loop for project ${projectId}.

Goal: keep the unresolved queue moving. Walk issues one at a time; for each one suggest an action; apply only after the user confirms.

**Step 1 — fetch the batch.** Call \`list_issues\` with:
\`\`\`json
{ "projectId": ${projectId}, "status": "unresolved", "sort": "lastSeenAt:desc", "limit": ${batchSize} }
\`\`\`

**Step 2 — walk each issue in order.** For every result:
a. Print one line: \`#<id> · <title> · level=<level> · count=<count> · lastSeen=<lastSeenAt> · assignee=<assigneeUserId or "—">\`.
b. Call \`get_issue\` with \`{ projectId: ${projectId}, issueId: <id> }\` for the full detail (latest 3 events + breadcrumbs included).
c. **Propose ONE** of these actions:
   - \`assign_issue\` to the developer whose recent commits touched the offending stack frames (use \`git blame\` if a repo is available).
   - \`triage_issue\` → \`status: "resolved"\` if recent occurrences trend to zero or the issue is a duplicate of a more recent one.
   - \`triage_issue\` → set \`firstSeenRelease\` if the field is empty and a release matches the stack-frame fingerprint window.
   - Leave as-is and skip.
d. **Wait for user confirmation**: "ok" / "skip" / "do X instead". Do NOT apply an action without an explicit "ok" or alternative.
e. On "ok", call the corresponding MCP tool. On "skip", move on.

**Step 3 — report.** After the batch, print counts: triaged, skipped, errored. Ask whether to fetch the next batch (re-run Step 1 with offset, or stop).

**Stop conditions**: the user says "stop", \`list_issues\` returns empty, or you hit two consecutive MCP errors.

Never invent issue ids — only act on data you've fetched in this session.`;
    },
  },
  {
    name: 'arguslog_release_postmortem',
    title: 'Release postmortem',
    description:
      'Auto-generate a Markdown postmortem for issues first seen in a given release. Groups by stack-frame fingerprint, hypothesises root cause, recommends actions.',
    arguments: [
      { name: 'projectId', description: 'Numeric project id.', required: true },
      {
        name: 'version',
        description: 'Release version string as it appears in Arguslog releases.',
        required: true,
      },
    ],
    body: (args) => {
      const projectId = requireArg(args, 'projectId', 'arguslog_release_postmortem');
      const version = requireArg(args, 'version', 'arguslog_release_postmortem');
      return `You are writing a release postmortem for project ${projectId}, release \`${version}\`.

**Step 1 — resolve the release.** Call \`list_release\` with \`{ projectId: ${projectId} }\`; find the entry whose version equals \`${version}\`. Capture its \`id\` as \`<releaseId>\`. If no match, stop and tell the user the version doesn't exist in Arguslog yet.

**Step 2 — fetch the issues introduced in this release.** Call \`list_issues\` with:
\`\`\`json
{ "projectId": ${projectId}, "firstSeenReleaseId": "<releaseId>", "limit": 50 }
\`\`\`

**Step 3 — pull detail.** For each issue (cap at 25), call \`get_issue\` and capture: title, level, count, lastSeen, top stack frame (file:line:function), and the most recent event's exception message.

**Step 4 — group by stack-frame fingerprint.** Issues that share the same top frame are likely one root cause — annotate accordingly.

**Step 5 — write the postmortem.** Produce a Markdown document with this structure:

\`\`\`markdown
# Postmortem — ${version}

**Released**: <date from the release entry>
**Issues introduced**: <count>
**Severity mix**: <breakdown by level>

## Top regressions
For each group (max 5):
- **<top frame>** — <count> events across <issue count> issues
  - Hypothesised root cause: <one sentence>
  - Recommended action: <one sentence — rollback / hotfix / monitor / assign>
  - Issues: #<id1>, #<id2>, …

## Timeline
<bullet list ordered by issue firstSeenAt>

## Recommended next steps
1. <ordered list>
2. ...
\`\`\`

**Step 6 — save.** If \`docs/postmortems/\` exists in the repo, write \`docs/postmortems/${version}.md\`. Otherwise print the Markdown to chat and tell the user where to save it.

Do not call any mutating MCP tools (no \`triage_issue\`, no \`assign_issue\`) — postmortem is read-only by design.`;
    },
  },
  {
    name: 'arguslog_regression_check',
    title: 'Regression check',
    description:
      'Diff the current release against the previous one — surfaces issues that are new or spiking. Pairs each finding with stack frames + git blame so triage decisions are immediate.',
    arguments: [
      { name: 'projectId', description: 'Numeric project id.', required: true },
      {
        name: 'currentVersion',
        description: 'The release you just shipped or are validating.',
        required: true,
      },
      {
        name: 'previousVersion',
        description: 'The reference release to diff against.',
        required: true,
      },
    ],
    body: (args) => {
      const projectId = requireArg(args, 'projectId', 'arguslog_regression_check');
      const currentVersion = requireArg(args, 'currentVersion', 'arguslog_regression_check');
      const previousVersion = requireArg(args, 'previousVersion', 'arguslog_regression_check');
      return `You are running a regression check for project ${projectId}: \`${previousVersion}\` → \`${currentVersion}\`.

**Step 1 — resolve both releases.** Call \`list_release\` and capture the ids for \`${currentVersion}\` and \`${previousVersion}\`. If either is missing, stop and tell the user.

**Step 2 — fetch issues for each release window.** Call \`list_issues\` twice:
- New in current: \`{ projectId: ${projectId}, firstSeenReleaseId: "<currentReleaseId>", limit: 50 }\`
- Active in previous (for spike detection): \`{ projectId: ${projectId}, seenInReleaseId: "<previousReleaseId>", limit: 100 }\`

**Step 3 — classify findings.**
- **NEW**: issues first seen in \`${currentVersion}\` only.
- **SPIKING**: issues present in both lists where current-window count is ≥3× previous-window count.

**Step 4 — detail + blame.** For each finding (cap at 15 total):
a. Call \`get_issue\` and capture top stack frame (file:line:function) + latest event message.
b. If the repo has a \`.git/\` directory, run \`git blame -L <line>,<line> <file>\` for the top frame and capture the most recent commit hash + author.

**Step 5 — report.** Print a table:

\`\`\`markdown
| Issue | Status | Count(new/prev) | Top frame | Likely author |
|---|---|---|---|---|
| #<id> | NEW or SPIKING | <count>/<count> | <file>:<line> | <author>(<sha>) |
\`\`\`

Below the table, suggest concrete next steps:
- Rollback if NEW count is high and recent commits look suspect.
- Assign each finding (no auto-apply — read-only by default; ask before calling \`assign_issue\`).
- Open a hotfix release if spikes correlate with one commit author.

Read-only by default. Only call \`triage_issue\` / \`assign_issue\` if the user explicitly says "apply".`;
    },
  },
  {
    name: 'arguslog_investigate_issue',
    title: 'Investigate single issue',
    description:
      'Deep-dive a single issue: detail + recent events + breadcrumbs → root-cause hypothesis with file:line references → action proposal.',
    arguments: [
      { name: 'projectId', description: 'Numeric project id.', required: true },
      {
        name: 'issueId',
        description: 'Numeric issue id from the dashboard URL or list_issues.',
        required: true,
      },
    ],
    body: (args) => {
      const projectId = requireArg(args, 'projectId', 'arguslog_investigate_issue');
      const issueId = requireArg(args, 'issueId', 'arguslog_investigate_issue');
      return `You are investigating Arguslog issue #${issueId} in project ${projectId}.

**Step 1 — fetch detail.** Call \`get_issue\` with \`{ projectId: ${projectId}, issueId: ${issueId} }\`. Capture title, level, count, firstSeenAt, lastSeenAt, current assignee, and the top stack frame.

**Step 2 — fetch recent events.** Call \`list_issue_events\` with \`{ projectId: ${projectId}, issueId: ${issueId}, limit: 5 }\`. Look at each event's exception chain, breadcrumbs (HTTP, console, navigation), and request context.

**Step 3 — hypothesise root cause.** Compose:
- The throwing line: \`<file>:<line> · <function>\` from the most-recent event.
- One-sentence hypothesis: what happened? Use the breadcrumbs + exception chain.
- File-level evidence: if the repo is checked out, read the offending file and show the actual code around the throwing line.
- Reproduction hint: the user request URL / payload / state from breadcrumbs if available.

**Step 4 — propose action.** Choose ONE:
- **Fix suggestion** — show a diff of the suggested code change (do not apply — present for user review).
- **Assign** — name a likely owner from \`git blame\` on the throwing frame.
- **Resolve as duplicate** — if you see an identical fingerprint in another open issue, link them.
- **Mark as not-a-bug** — if breadcrumbs show it's expected user behaviour (e.g., 404 on a deleted resource).

**Step 5 — wait for user.** Ask "what would you like to do?" Only call mutating MCP tools (\`triage_issue\`, \`assign_issue\`) after explicit user confirmation.`;
    },
  },
];

/** Returns the MCP-Spec-conformant prompts list (no `body` function, no extra fields). */
export function listMcpPrompts(): Prompt[] {
  return WORKFLOWS.map(({ name, title, description, arguments: args }) => ({
    name,
    title,
    description,
    arguments: args.map((a) => ({
      name: a.name,
      description: a.description,
      required: a.required ?? false,
    })),
  }));
}

/** Resolves a workflow by name and renders its body with the supplied arguments. */
export function getMcpPrompt(
  name: string,
  args: Record<string, string | undefined>,
): GetPromptResult {
  const workflow = WORKFLOWS.find((w) => w.name === name);
  if (!workflow) {
    throw new Error(
      `Unknown Arguslog workflow "${name}". Available: ${WORKFLOWS.map((w) => w.name).join(', ')}.`,
    );
  }
  const text = workflow.body(args);
  return {
    description: workflow.description,
    messages: [{ role: 'user', content: { type: 'text', text } }],
  };
}
