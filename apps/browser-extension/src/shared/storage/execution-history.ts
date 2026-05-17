/**
 * Per-operator log of MCP tool executions — args + outcome + truncated result. The
 * History screen renders this newest-first and lets the operator click „Rerun" on any
 * entry to pre-fill the ToolsScreen form.
 *
 * Storage decisions (per the Phase 2 plan):
 * - `browser.storage.local`, single key `'execution.history'`. Quota is 5 MB; our cap
 *   keeps usage in the low-KB range.
 * - Rotation cap: 200 entries newest-first. 201st `appendExecution` drops the oldest.
 * - Result truncation: anything > 2 KB (after JSON-stringify) is stored as the first
 *   2 KB chars + a `truncated: true` flag. Args are not truncated — they're usually
 *   small and the operator needs the exact shape for Rerun.
 *
 * `clearExecutionHistory()` is exposed for the Settings „nuke" affordance + tests.
 *
 * `appendExecution` swallows storage errors (logs + drops). A storage hiccup must NOT
 * fail the underlying tool call that the operator initiated.
 */
import browser from 'webextension-polyfill';
import { z } from 'zod';

import { readVersioned, writeVersioned } from './schema-version';

const HISTORY_KEY = 'execution.history';
const HISTORY_CAP = 200;
const RESULT_SUMMARY_BUDGET = 2048;
const HISTORY_SCHEMA_VERSION = 1;

const ToolExecutionSchema = z.object({
  id: z.string(),
  ts: z.string(),
  toolName: z.string(),
  args: z.record(z.unknown()),
  outcome: z.enum(['ok', 'error']),
  durationMs: z.number().nonnegative(),
  resultSummary: z.string().optional(),
  errorBucket: z.string().optional(),
  errorMessage: z.string().optional(),
  truncated: z.boolean().optional(),
  // Phase 3: attribution to a workflow run so HistoryScreen can group consecutive
  // entries under their parent workflow row. Absent for standalone tool calls.
  workflowRunId: z.string().optional(),
});

const HistoryArraySchema = z.array(ToolExecutionSchema);

export type ToolExecution = z.infer<typeof ToolExecutionSchema>;

export async function getExecutionHistory(): Promise<ToolExecution[]> {
  return readVersioned({
    area: browser.storage.local as unknown as chrome.storage.StorageArea,
    key: HISTORY_KEY,
    currentVersion: HISTORY_SCHEMA_VERSION,
    schema: HistoryArraySchema,
    defaults: [],
  });
}

export async function appendExecution(
  partial: Omit<ToolExecution, 'id' | 'ts' | 'truncated' | 'resultSummary'> & {
    result?: unknown;
  },
): Promise<void> {
  try {
    const existing = await getExecutionHistory();
    const entry = buildEntry(partial);
    const next = [entry, ...existing].slice(0, HISTORY_CAP);
    await writeVersioned(
      browser.storage.local as unknown as chrome.storage.StorageArea,
      HISTORY_KEY,
      HISTORY_SCHEMA_VERSION,
      next,
    );
  } catch (err) {
    // Tool call must succeed even when history can't be persisted.
    console.warn('[execution-history] append failed:', err);
  }
}

export async function clearExecutionHistory(): Promise<void> {
  await browser.storage.local.remove(HISTORY_KEY);
}

function buildEntry(
  partial: Omit<ToolExecution, 'id' | 'ts' | 'truncated' | 'resultSummary'> & {
    result?: unknown;
  },
): ToolExecution {
  const { result, ...rest } = partial;
  const { summary, truncated } = summarizeResult(result);
  return {
    id: cryptoRandomId(),
    ts: new Date().toISOString(),
    ...rest,
    resultSummary: summary,
    truncated: truncated || undefined,
  };
}

function summarizeResult(result: unknown): { summary?: string; truncated: boolean } {
  if (result === undefined) return { truncated: false };
  let json: string;
  try {
    json = JSON.stringify(result);
  } catch {
    return { summary: '[unserializable result]', truncated: false };
  }
  if (json === undefined) return { truncated: false };
  if (json.length <= RESULT_SUMMARY_BUDGET) {
    return { summary: json, truncated: false };
  }
  return {
    summary: json.slice(0, RESULT_SUMMARY_BUDGET),
    truncated: true,
  };
}

function cryptoRandomId(): string {
  // `crypto.randomUUID` is available in all modern browsers + the WXT environment.
  // Fall back to a Math.random id only if the test/JSDOM environment somehow lacks it.
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}
