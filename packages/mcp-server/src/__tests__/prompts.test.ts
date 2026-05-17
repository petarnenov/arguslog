/**
 * Tests for the Arguslog MCP prompts capability — the canned workflow catalog behind the
 * landing-page slogan "Read · Eval · Triage · Loop". listMcpPrompts is the wire shape every
 * `prompts/list` JSON-RPC response carries; getMcpPrompt is what `prompts/get` returns.
 */
import { describe, expect, it } from 'vitest';

import { getMcpPrompt, listMcpPrompts, WORKFLOWS } from '../prompts.js';

describe('listMcpPrompts', () => {
  it('returns exactly four workflows', () => {
    const prompts = listMcpPrompts();
    expect(prompts).toHaveLength(4);
    expect(prompts.map((p) => p.name)).toEqual([
      'arguslog_triage_loop',
      'arguslog_release_postmortem',
      'arguslog_regression_check',
      'arguslog_investigate_issue',
    ]);
  });

  it('declares argument schemas with required flags', () => {
    const triage = listMcpPrompts().find((p) => p.name === 'arguslog_triage_loop');
    expect(triage?.arguments).toContainEqual({
      name: 'projectId',
      description: expect.any(String),
      required: true,
    });
    // batchSize is optional.
    const batch = triage?.arguments?.find((a) => a.name === 'batchSize');
    expect(batch?.required).toBe(false);
  });

  it('every workflow has title + description that are non-empty', () => {
    for (const w of WORKFLOWS) {
      expect(w.title.length).toBeGreaterThan(0);
      expect(w.description.length).toBeGreaterThan(0);
    }
  });
});

describe('getMcpPrompt', () => {
  it('renders the triage-loop body with the supplied projectId + batchSize', () => {
    const result = getMcpPrompt('arguslog_triage_loop', { projectId: '42', batchSize: '5' });
    const text = (result.messages[0]!.content as { type: 'text'; text: string }).text;
    expect(text).toContain('project 42');
    expect(text).toContain('"limit": 5');
    expect(text).toContain('list_issues');
    expect(text).toContain('triage_issue');
    expect(text).toContain('assign_issue');
  });

  it('defaults batchSize to 10 when omitted', () => {
    const result = getMcpPrompt('arguslog_triage_loop', { projectId: '7' });
    const text = (result.messages[0]!.content as { type: 'text'; text: string }).text;
    expect(text).toContain('"limit": 10');
  });

  it('release-postmortem body references list_release + git-blame guidance', () => {
    const result = getMcpPrompt('arguslog_release_postmortem', {
      projectId: '7',
      version: '1.4.2',
    });
    const text = (result.messages[0]!.content as { type: 'text'; text: string }).text;
    expect(text).toContain('list_release');
    expect(text).toContain('1.4.2');
    expect(text).toContain('# Postmortem');
    // Read-only: must not tell the agent to mutate.
    expect(text).toMatch(/Do not call any mutating MCP tools/);
  });

  it('regression-check body lists both releases and uses get_issue', () => {
    const result = getMcpPrompt('arguslog_regression_check', {
      projectId: '7',
      currentVersion: '2.0.0',
      previousVersion: '1.9.0',
    });
    const text = (result.messages[0]!.content as { type: 'text'; text: string }).text;
    expect(text).toContain('2.0.0');
    expect(text).toContain('1.9.0');
    expect(text).toContain('git blame');
    expect(text).toContain('list_issues');
  });

  it('investigate-issue body references list_issue_events + read-only mutating-tool guard', () => {
    const result = getMcpPrompt('arguslog_investigate_issue', { projectId: '7', issueId: '101' });
    const text = (result.messages[0]!.content as { type: 'text'; text: string }).text;
    expect(text).toContain('#101');
    expect(text).toContain('list_issue_events');
    expect(text).toContain('explicit user confirmation');
  });

  it('throws a helpful error when an unknown workflow name is requested', () => {
    expect(() => getMcpPrompt('arguslog_does_not_exist', {})).toThrow(/Available:/);
  });

  it('throws a missing-arg error when a required argument is omitted', () => {
    expect(() => getMcpPrompt('arguslog_triage_loop', {})).toThrow(/projectId/);
    expect(() => getMcpPrompt('arguslog_release_postmortem', { projectId: '1' })).toThrow(
      /version/,
    );
  });
});
