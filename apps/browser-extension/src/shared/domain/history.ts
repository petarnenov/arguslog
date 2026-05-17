/**
 * Domain-layer accessors for the execution-history store. Goes through
 * `sendBackgroundRequest` — the same indirection the workspace / page-context paths use —
 * so the sidepanel can read the same storage that the background's `callTool`
 * instrumentation writes.
 */
import { z } from 'zod';

import { sendBackgroundRequest } from '../utils/messaging';

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
});

export type ToolExecution = z.infer<typeof ToolExecutionSchema>;

export async function listExecutionHistory(): Promise<ToolExecution[]> {
  return sendBackgroundRequest({ type: 'execution-history/get' }, z.array(ToolExecutionSchema));
}

export async function clearExecutionHistoryDomain(): Promise<void> {
  await sendBackgroundRequest(
    { type: 'execution-history/clear' },
    z.object({ cleared: z.boolean() }),
  );
}
