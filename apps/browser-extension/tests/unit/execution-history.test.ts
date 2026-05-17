/**
 * Tests for the execution-history storage module. Mocks `browser.storage.local` (via the
 * webextension-polyfill global in the test setup) and exercises rotation + truncation.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const storage = new Map<string, unknown>();

vi.mock('webextension-polyfill', () => ({
  default: {
    storage: {
      local: {
        get: async (key: string) => {
          const value = storage.get(key);
          return value === undefined ? {} : { [key]: value };
        },
        set: async (entries: Record<string, unknown>) => {
          for (const [k, v] of Object.entries(entries)) storage.set(k, v);
        },
        remove: async (key: string) => {
          storage.delete(key);
        },
      },
    },
  },
}));

// Import AFTER the mock so the module picks up the stubbed `browser`.
const { appendExecution, clearExecutionHistory, getExecutionHistory } =
  await import('../../src/shared/storage/execution-history');

describe('execution-history', () => {
  beforeEach(() => {
    storage.clear();
  });

  it('starts empty', async () => {
    expect(await getExecutionHistory()).toEqual([]);
  });

  it('append roundtrips with structured fields', async () => {
    await appendExecution({
      toolName: 'list_issues',
      args: { projectId: 1 },
      outcome: 'ok',
      durationMs: 42,
      result: { data: [{ id: 7 }] },
    });
    const entries = await getExecutionHistory();
    expect(entries).toHaveLength(1);
    expect(entries[0]?.toolName).toBe('list_issues');
    expect(entries[0]?.args).toEqual({ projectId: 1 });
    expect(entries[0]?.outcome).toBe('ok');
    expect(entries[0]?.resultSummary).toContain('"id":7');
    expect(entries[0]?.truncated).toBeUndefined();
    expect(entries[0]?.id).toBeTypeOf('string');
    expect(entries[0]?.ts).toBeTypeOf('string');
  });

  it('records error entries with bucket + message', async () => {
    await appendExecution({
      toolName: 'create_release',
      args: { projectId: 2, body: { version: 'v1' } },
      outcome: 'error',
      durationMs: 13,
      errorBucket: 'INVALID_PAT',
      errorMessage: 'PAT lacks releases:write',
    });
    const entries = await getExecutionHistory();
    expect(entries[0]?.errorBucket).toBe('INVALID_PAT');
    expect(entries[0]?.errorMessage).toBe('PAT lacks releases:write');
    expect(entries[0]?.resultSummary).toBeUndefined();
  });

  it('appends newest-first', async () => {
    await appendExecution({
      toolName: 'first',
      args: {},
      outcome: 'ok',
      durationMs: 1,
    });
    await appendExecution({
      toolName: 'second',
      args: {},
      outcome: 'ok',
      durationMs: 1,
    });
    const entries = await getExecutionHistory();
    expect(entries[0]?.toolName).toBe('second');
    expect(entries[1]?.toolName).toBe('first');
  });

  it('caps at 200 entries — 201st append drops oldest', async () => {
    for (let i = 0; i < 201; i++) {
      await appendExecution({
        toolName: `tool-${i}`,
        args: {},
        outcome: 'ok',
        durationMs: 1,
      });
    }
    const entries = await getExecutionHistory();
    expect(entries).toHaveLength(200);
    // Newest is tool-200, oldest in the cap is tool-1 (tool-0 was rotated out).
    expect(entries[0]?.toolName).toBe('tool-200');
    expect(entries[199]?.toolName).toBe('tool-1');
  });

  it('truncates results larger than 2 KB with a flag', async () => {
    const huge = { blob: 'x'.repeat(3000) };
    await appendExecution({
      toolName: 'list_huge',
      args: {},
      outcome: 'ok',
      durationMs: 1,
      result: huge,
    });
    const entries = await getExecutionHistory();
    expect(entries[0]?.truncated).toBe(true);
    expect(entries[0]?.resultSummary?.length).toBeLessThanOrEqual(2048);
  });

  it('clearExecutionHistory empties the store', async () => {
    await appendExecution({
      toolName: 'x',
      args: {},
      outcome: 'ok',
      durationMs: 1,
    });
    expect(await getExecutionHistory()).toHaveLength(1);
    await clearExecutionHistory();
    expect(await getExecutionHistory()).toEqual([]);
  });
});
