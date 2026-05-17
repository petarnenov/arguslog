import { z } from 'zod';

import {
  McpPromptSchema,
  McpToolSchema,
  type McpPromptDefinition,
  type McpToolDefinition,
} from '../mcp/protocol';
import { sendBackgroundRequest } from '../utils/messaging';

export async function listCatalogTools(): Promise<McpToolDefinition[]> {
  return sendBackgroundRequest({ type: 'catalog/tools' }, z.array(McpToolSchema));
}

export async function listCatalogPrompts(): Promise<McpPromptDefinition[]> {
  return sendBackgroundRequest({ type: 'catalog/prompts' }, z.array(McpPromptSchema));
}

export async function getPromptText(name: string, args: Record<string, string>) {
  return sendBackgroundRequest(
    {
      type: 'prompt/get',
      payload: { name, arguments: args },
    },
    z.object({
      description: z.string().optional(),
      text: z.string(),
    }),
  );
}

/**
 * Module-scoped active workflow-run id. Set by `withWorkflowRun` while a workflow step is
 * in flight; `callRawTool` reads it and threads it through to the background so the
 * resulting execution-history entry gets `workflowRunId` stamped. Used instead of an
 * explicit parameter so the dozens of existing domain wrappers (`getIssue`, `listIssues`,
 * …) don't need to be widened. Side-panel steps run sequentially, so the module-level
 * scope is safe — no concurrent runs ever overlap.
 */
let activeWorkflowRunId: string | undefined;

export async function withWorkflowRun<T>(runId: string, fn: () => Promise<T>): Promise<T> {
  const prev = activeWorkflowRunId;
  activeWorkflowRunId = runId;
  try {
    return await fn();
  } finally {
    activeWorkflowRunId = prev;
  }
}

export async function callRawTool(
  name: string,
  args: Record<string, unknown>,
  expectMutation = false,
) {
  return sendBackgroundRequest(
    {
      type: 'tool/call',
      payload: activeWorkflowRunId
        ? { name, args, expectMutation, workflowRunId: activeWorkflowRunId }
        : { name, args, expectMutation },
    },
    z.unknown(),
  );
}
