import { describe, expect, it } from 'vitest';

import { buildSnippets, type SnippetContext } from '../../lib/connectSnippets';

const baseCtx: SnippetContext = {
  dsn: 'arguslog://abc123@ingest.arguslog.org/api/42',
  pat: 'arglog_pat_xyz789',
  apiUrl: 'https://arguslog.org',
};

describe('buildSnippets', () => {
  it('produces the full catalog grouped sdk/mcp/cli', () => {
    const all = buildSnippets(baseCtx);
    const ids = all.map((s) => s.id);

    // SDK: 5, MCP: 4, CLI: 1
    expect(all.filter((s) => s.group === 'sdk')).toHaveLength(5);
    expect(all.filter((s) => s.group === 'mcp')).toHaveLength(4);
    expect(all.filter((s) => s.group === 'cli')).toHaveLength(1);
    expect(ids).toEqual([
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
    ]);
  });

  it('inlines the DSN into every SDK snippet', () => {
    const all = buildSnippets(baseCtx);
    for (const s of all.filter((x) => x.group === 'sdk')) {
      expect(s.code).toContain(baseCtx.dsn);
    }
  });

  it('inlines the PAT into every MCP + CLI snippet', () => {
    const all = buildSnippets(baseCtx);
    for (const s of all.filter((x) => x.group === 'mcp' || x.group === 'cli')) {
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
    const languages = new Set(['tsx', 'ts', 'js', 'python', 'java', 'json', 'bash']);
    for (const s of buildSnippets(baseCtx)) {
      expect(languages.has(s.language)).toBe(true);
    }
  });
});
