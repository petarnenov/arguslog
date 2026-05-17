import { describe, expect, it } from 'vitest';

import {
  buildAgentPrompt,
  buildSnippets,
  SDK_CATALOG,
  type SnippetContext,
} from '../../lib/connectSnippets';

const baseCtx: SnippetContext = {
  dsn: 'arguslog://abc123@ingest.arguslog.org/api/42',
  pat: 'arglog_pat_xyz789',
  apiUrl: 'https://arguslog.org',
};

describe('buildSnippets', () => {
  it('produces the full catalog grouped agent/workflow/sdk/mcp/cli', () => {
    const all = buildSnippets(baseCtx);
    const ids = all.map((s) => s.id);

    // Agent: 6, Workflow: 4 (triage / postmortem / regression / investigate),
    // SDK: 6 (vue added in Phase B for the workflow-first onboarding flow),
    // MCP: 4, CLI: 2.
    expect(all.filter((s) => s.group === 'agent')).toHaveLength(6);
    expect(all.filter((s) => s.group === 'workflow')).toHaveLength(4);
    expect(all.filter((s) => s.group === 'sdk')).toHaveLength(6);
    expect(all.filter((s) => s.group === 'mcp')).toHaveLength(4);
    expect(all.filter((s) => s.group === 'cli')).toHaveLength(2);
    expect(ids).toEqual([
      'agent-claude-code',
      'agent-cursor',
      'agent-codex',
      'agent-copilot',
      'agent-windsurf',
      'agent-continue',
      'workflow-triage-loop',
      'workflow-release-postmortem',
      'workflow-regression-check',
      'workflow-investigate-issue',
      'sdk-javascript',
      'sdk-react',
      'sdk-vue',
      'sdk-node',
      'sdk-python',
      'sdk-java',
      'mcp-claude-desktop',
      'mcp-cursor',
      'mcp-claude-code',
      'mcp-continue',
      'cli',
      'curl-test',
    ]);
  });

  it('workflow bodies reference the right MCP tools and stay read-only by default', () => {
    const all = buildSnippets(baseCtx);
    const triage = all.find((s) => s.id === 'workflow-triage-loop')!;
    expect(triage.code).toContain('list_issues');
    expect(triage.code).toContain('triage_issue');
    expect(triage.code).toContain('assign_issue');
    expect(triage.code).toContain('<PROJECT_ID>');

    const postmortem = all.find((s) => s.id === 'workflow-release-postmortem')!;
    expect(postmortem.code).toContain('list_release');
    expect(postmortem.code).toContain('# Postmortem — <VERSION>');
    // Postmortem is read-only — must explicitly forbid mutations.
    expect(postmortem.code).toMatch(/do not call any mutating MCP tools/i);

    const regression = all.find((s) => s.id === 'workflow-regression-check')!;
    expect(regression.code).toContain('<CURRENT_VERSION>');
    expect(regression.code).toContain('<PREVIOUS_VERSION>');
    expect(regression.code).toContain('git blame');

    const investigate = all.find((s) => s.id === 'workflow-investigate-issue')!;
    expect(investigate.code).toContain('list_issue_events');
    expect(investigate.code).toContain('<ISSUE_ID>');
    expect(investigate.code).toContain('explicit confirmation');
  });

  it('curl-test snippet uses the DSN public key + ingest URL', () => {
    const curl = buildSnippets(baseCtx).find((s) => s.id === 'curl-test');
    expect(curl?.code).toContain('ingest.arguslog.org/api/42/events');
    expect(curl?.code).toContain('X-Arguslog-Auth: Arguslog DSN abc123');
    expect(curl?.code).toContain('ArguslogConnectivityProbe');
  });

  it('curl-test snippet falls back to placeholders without a DSN', () => {
    const curl = buildSnippets({ ...baseCtx, dsn: null }).find((s) => s.id === 'curl-test');
    expect(curl?.code).toContain('<INGEST_URL>');
    expect(curl?.code).toContain('<PUBLIC_KEY>');
    expect(curl?.code).toContain('<PROJECT_ID>');
  });

  it('inlines the DSN into every SDK snippet', () => {
    const all = buildSnippets(baseCtx);
    for (const s of all.filter((x) => x.group === 'sdk')) {
      expect(s.code).toContain(baseCtx.dsn);
    }
  });

  it('inlines the PAT into every MCP + CLI snippet (except curl-test which is DSN-auth)', () => {
    const all = buildSnippets(baseCtx);
    for (const s of all.filter(
      (x) => (x.group === 'mcp' || x.group === 'cli') && x.id !== 'curl-test',
    )) {
      expect(s.code).toContain(baseCtx.pat);
    }
  });

  it('falls back to a placeholder when DSN is missing', () => {
    const snippets = buildSnippets({ ...baseCtx, dsn: null });
    const browser = snippets.find((s) => s.id === 'sdk-javascript');
    expect(browser?.code).toContain('<GENERATE_DSN_FIRST>');
    expect(browser?.code).not.toContain('arguslog://');
  });

  it('falls back to a placeholder when PAT is missing', () => {
    const snippets = buildSnippets({ ...baseCtx, pat: null });
    const claude = snippets.find((s) => s.id === 'mcp-claude-desktop');
    expect(claude?.code).toContain('<GENERATE_PAT_FIRST>');
    expect(claude?.code).not.toContain('arglog_pat_');
  });

  it('omits the ARGUSLOG_API_URL env var for the default arguslog.org host', () => {
    const all = buildSnippets(baseCtx);
    for (const s of all.filter((x) => x.group === 'mcp' || x.group === 'cli')) {
      expect(s.code).not.toContain('ARGUSLOG_API_URL');
    }
  });

  it('emits ARGUSLOG_API_URL for self-hosted setups', () => {
    const all = buildSnippets({ ...baseCtx, apiUrl: 'https://arguslog.acme.internal' });
    const claude = all.find((s) => s.id === 'mcp-claude-desktop');
    expect(claude?.code).toContain('ARGUSLOG_API_URL');
    expect(claude?.code).toContain('arguslog.acme.internal');

    const cli = all.find((s) => s.id === 'cli');
    expect(cli?.code).toContain('export ARGUSLOG_API_URL');
  });

  it('keeps every snippet typed for a known syntax-highlight language', () => {
    const languages = new Set(['tsx', 'ts', 'js', 'python', 'java', 'json', 'bash', 'markdown']);
    for (const s of buildSnippets(baseCtx)) {
      expect(languages.has(s.language)).toBe(true);
    }
  });
});

describe('buildAgentPrompt', () => {
  const ALL_AGENTS = ['claude-code', 'cursor', 'codex', 'copilot', 'windsurf', 'continue'] as const;

  it('renders a self-describing brief for every supported agent', () => {
    for (const agent of ALL_AGENTS) {
      const md = buildAgentPrompt(baseCtx, agent);
      // Title + step headings present.
      expect(md).toContain('# Integrate Arguslog');
      expect(md).toContain('## Step 1 — detect the stack');
      expect(md).toContain('## Step 2 — install the SDK');
      expect(md).toContain('## Step 3 — register the Arguslog MCP server');
      expect(md).toContain('## Step 4 — verify');
      expect(md).toContain('## Step 5 — report');
      // SDK detection table covers every catalog entry so the agent can match any stack.
      for (const p of SDK_CATALOG) {
        expect(md).toContain(`\`${p.slug}\``);
        expect(md).toContain(`${p.pkg}@${p.version}`);
      }
      // Credentials block carries both secrets inline. The PAT must appear at the exact
      // place the agent reads (Authorization header or env var) — the invariant under test.
      expect(md).toContain(baseCtx.dsn!);
      expect(md).toContain(baseCtx.pat!);
      // Escape-hatch paragraph must always be present so the user knows how to rotate.
      expect(md).toMatch(/Revoking or rotating later/i);
      expect(md).toContain('https://app.arguslog.org/me/tokens');
      expect(md).toMatch(/delete_me_tokens/);
    }
  });

  it('uses hosted MCP URL for every agent (all six speak Streamable HTTP)', () => {
    for (const agent of ALL_AGENTS) {
      const md = buildAgentPrompt(baseCtx, agent);
      expect(md).toContain('https://mcp.arguslog.org/mcp');
    }
  });

  it('per-agent schema matches the docs (regression guard for V3.1)', () => {
    // Claude Code — explicit "type": "http" in JSON, even though the CLI command also works.
    expect(buildAgentPrompt(baseCtx, 'claude-code')).toMatch(/"type":\s*"http"/);

    // Codex — TOML, never Claude-style JSON. Block header [mcp_servers.arguslog] is the
    // smoking gun for the correct schema.
    const codex = buildAgentPrompt(baseCtx, 'codex');
    expect(codex).toContain('[mcp_servers.arguslog]');
    expect(codex).toContain('http_headers');
    expect(codex).not.toMatch(/"mcpServers"\s*:/); // no JSON shape leaking in

    // Windsurf — `serverUrl` (Codeium docs), NOT `url`.
    const windsurf = buildAgentPrompt(baseCtx, 'windsurf');
    expect(windsurf).toContain('"serverUrl"');
    expect(windsurf).not.toMatch(/"url":\s*"https:\/\/mcp\.arguslog\.org/);

    // Continue — workspace YAML (1.0+), not the deprecated experimental.* JSON. The
    // prose mentions the deprecated path by name so the agent understands the migration,
    // so we only assert the YAML schema is the one being emitted for the agent to write.
    const cont = buildAgentPrompt(baseCtx, 'continue');
    expect(cont).toContain('.continue/mcpServers/arguslog.yaml');
    expect(cont).toContain('type: streamable-http');
    // The actual server-config code block is YAML — no JSON `"experimental"` wrapper.
    expect(cont).not.toMatch(/"experimental"\s*:/);

    // Copilot — dual-path (Chat .vscode/mcp.json with `servers` + CLI .mcp.json with
    // `mcpServers`). Both blocks must carry explicit "type": "http".
    const copilot = buildAgentPrompt(baseCtx, 'copilot');
    expect(copilot).toContain('.vscode/mcp.json');
    expect(copilot).toContain('.mcp.json');
    expect(copilot).toContain('~/.copilot/mcp-config.json');
    // Two HTTP type declarations (one per file).
    expect((copilot.match(/"type":\s*"http"/g) ?? []).length).toBeGreaterThanOrEqual(2);
  });

  it('does not treat git status as a required step (workspace may not be a git repo)', () => {
    const md = buildAgentPrompt(baseCtx, 'claude-code');
    expect(md).toMatch(/may or may not be a git repository/i);
    // The "report" step should not order the agent to run git status as if it were mandatory.
    expect(md).not.toMatch(/Run `git status` and list/);
  });

  it('explicitly tells the agent not to list secret-replacement as a manual TODO', () => {
    const md = buildAgentPrompt(baseCtx, 'cursor');
    expect(md).toMatch(/Do \*\*not\*\* list "replace the PAT"/);
  });

  it('Step 2 carries default integrations + framework wraps for every supported stack', () => {
    // Step 2 content is agent-agnostic; pick any agent to retrieve the block.
    const md = buildAgentPrompt(baseCtx, 'claude-code');

    // Browser-family SDKs default to globalHandlers + autoBreadcrumbs.
    const browserIntegrations = "integrations: ['globalHandlers', 'autoBreadcrumbs']";
    expect(
      md.match(new RegExp(browserIntegrations.replace(/[[\]]/g, '\\$&'), 'g'))?.length ?? 0,
    ).toBeGreaterThanOrEqual(5); // javascript, react, angular, vue, nextjs (client), web3

    // Server SDKs use processHandlers + http.
    expect(md).toContain("integrations: ['processHandlers', 'http']");

    // React Native ships only globalHandlers (no DOM, no breadcrumbs).
    expect(md).toContain("integrations: ['globalHandlers']");

    // Framework wraps for each UI SDK.
    expect(md).toContain('<ArguslogErrorBoundary fallback={<p>Something went wrong.</p>}>');
    expect(md).toContain('<ArguslogErrorBoundary fallback={<CrashScreen />}>'); // RN variant
    expect(md).toContain('provideArguslog('); // Angular
    expect(md).toContain('createArguslog('); // Vue — factory exported by @arguslog/sdk-vue
    expect(md).not.toContain('arguslogPlugin'); // Regression guard: dashboard once shipped a non-existent symbol.

    // Vue env-driven installer shape (Phase A onboarding rework, issue #2).
    // The DSN lives in .env.local; the installer module reads it at build time and
    // no-ops when missing; main.ts calls a named `installArguslog(app)` rather than
    // inline `app.use(createArguslog(...))`.
    expect(md).toContain('VITE_ARGUSLOG_DSN=<DSN>');
    expect(md).toContain('installArguslog(app)');
    expect(md).toContain('export function installArguslog');
    // ErrorBoundary uses the `fallback` prop, not slot syntax (runtime requires it).
    expect(md).toContain(':fallback="errorFallback"');
    expect(md).not.toContain('<template #fallback');
    expect(md).toContain('instrumentation.ts'); // Next.js server
    expect(md).toContain('onRequestError'); // Next.js error hook

    // Server-side wiring.
    expect(md).toContain("process.on('unhandledRejection'");
    expect(md).toContain('captureException');

    // Python init kwargs.
    expect(md).toContain('install_excepthook=True');
    expect(md).toContain('install_logging_handler=30');

    // Java/Spring autoconfig YAML — shows up as a code block headed with `arguslog:` /
    // `dsn:` keys.
    expect(md).toMatch(/arguslog:\s*\n\s+dsn: "<DSN>"/);

    // web3 augmentation.
    expect(md).toContain('initWeb3(');
  });

  it('points each agent at its canonical MCP config file', () => {
    expect(buildAgentPrompt(baseCtx, 'claude-code')).toContain('.mcp.json');
    expect(buildAgentPrompt(baseCtx, 'claude-code')).toContain('claude mcp add arguslog');
    expect(buildAgentPrompt(baseCtx, 'cursor')).toContain('.cursor/mcp.json');
    expect(buildAgentPrompt(baseCtx, 'codex')).toContain('~/.codex/config.toml');
    expect(buildAgentPrompt(baseCtx, 'copilot')).toContain('.vscode/mcp.json');
    expect(buildAgentPrompt(baseCtx, 'copilot')).toContain('.mcp.json');
    expect(buildAgentPrompt(baseCtx, 'copilot')).toContain('gh.io/copilotcli-mcpmigrate');
    expect(buildAgentPrompt(baseCtx, 'windsurf')).toContain('.codeium/windsurf/mcp_config.json');
    expect(buildAgentPrompt(baseCtx, 'continue')).toContain('.continue/mcpServers/arguslog.yaml');
  });

  it('falls back to placeholders when DSN or PAT are missing', () => {
    const md = buildAgentPrompt({ ...baseCtx, dsn: null, pat: null }, 'claude-code');
    expect(md).toContain('<GENERATE_DSN_FIRST>');
    expect(md).toContain('<GENERATE_PAT_FIRST>');
    expect(md).not.toContain('arguslog://abc123');
    expect(md).not.toContain('arglog_pat_xyz789');
  });

  it('emits a self-hosted MCP URL hint when apiUrl is not arguslog.org', () => {
    const md = buildAgentPrompt({ ...baseCtx, apiUrl: 'https://arguslog.acme.internal' }, 'cursor');
    expect(md).toContain('ARGUSLOG_API_URL');
    expect(md).toContain('arguslog.acme.internal');
    // Hosted URL should NOT be inlined when self-hosted — the prompt instructs the agent to
    // run mcp-server locally instead of pointing at our cloud.
    expect(md).not.toContain('https://mcp.arguslog.org/mcp');
  });
});
