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
  it('produces the full catalog grouped agent/sdk/mcp/cli', () => {
    const all = buildSnippets(baseCtx);
    const ids = all.map((s) => s.id);

    // Agent: 7 (claude-code, cursor, codex, copilot, windsurf, continue, aider),
    // SDK: 5, MCP: 4, CLI: 2.
    expect(all.filter((s) => s.group === 'agent')).toHaveLength(7);
    expect(all.filter((s) => s.group === 'sdk')).toHaveLength(5);
    expect(all.filter((s) => s.group === 'mcp')).toHaveLength(4);
    expect(all.filter((s) => s.group === 'cli')).toHaveLength(2);
    expect(ids).toEqual([
      'agent-claude-code',
      'agent-cursor',
      'agent-codex',
      'agent-copilot',
      'agent-windsurf',
      'agent-continue',
      'agent-aider',
      'sdk-javascript',
      'sdk-react',
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
  const ALL_AGENTS = [
    'claude-code',
    'cursor',
    'codex',
    'copilot',
    'windsurf',
    'continue',
    'aider',
  ] as const;

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

  it('uses hosted MCP URL for every HTTP-transport agent (not Aider)', () => {
    for (const agent of ['claude-code', 'cursor', 'codex', 'copilot', 'windsurf', 'continue'] as const) {
      const md = buildAgentPrompt(baseCtx, agent);
      expect(md).toContain('https://mcp.arguslog.org/mcp');
    }
  });

  it('aider prompt uses stdio (npx) — hosted HTTP URL never appears, PAT goes through env var', () => {
    const md = buildAgentPrompt(baseCtx, 'aider');
    expect(md).toContain('npx');
    expect(md).toContain('@arguslog/mcp-server');
    expect(md).toContain('ARGUSLOG_PAT:');
    expect(md).toContain(baseCtx.pat!);
    // No hosted MCP URL — Aider can't speak it.
    expect(md).not.toContain('https://mcp.arguslog.org/mcp');
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

  it('points each agent at its canonical MCP config file', () => {
    expect(buildAgentPrompt(baseCtx, 'claude-code')).toContain('.mcp.json');
    expect(buildAgentPrompt(baseCtx, 'claude-code')).toContain('claude mcp add arguslog');
    expect(buildAgentPrompt(baseCtx, 'cursor')).toContain('.cursor/mcp.json');
    expect(buildAgentPrompt(baseCtx, 'codex')).toContain('.mcp.json');
    expect(buildAgentPrompt(baseCtx, 'copilot')).toContain('.vscode/mcp.json');
    expect(buildAgentPrompt(baseCtx, 'windsurf')).toContain('.codeium/windsurf/mcp_config.json');
    expect(buildAgentPrompt(baseCtx, 'continue')).toContain('.continue/config.json');
    expect(buildAgentPrompt(baseCtx, 'aider')).toContain('.aider.conf.yml');
  });

  it('falls back to placeholders when DSN or PAT are missing', () => {
    const md = buildAgentPrompt({ ...baseCtx, dsn: null, pat: null }, 'claude-code');
    expect(md).toContain('<GENERATE_DSN_FIRST>');
    expect(md).toContain('<GENERATE_PAT_FIRST>');
    expect(md).not.toContain('arguslog://abc123');
    expect(md).not.toContain('arglog_pat_xyz789');
  });

  it('emits a self-hosted MCP URL hint when apiUrl is not arguslog.org', () => {
    const md = buildAgentPrompt(
      { ...baseCtx, apiUrl: 'https://arguslog.acme.internal' },
      'cursor',
    );
    expect(md).toContain('ARGUSLOG_API_URL');
    expect(md).toContain('arguslog.acme.internal');
    // Hosted URL should NOT be inlined when self-hosted — the prompt instructs the agent to
    // run mcp-server locally instead of pointing at our cloud.
    expect(md).not.toContain('https://mcp.arguslog.org/mcp');
  });
});
