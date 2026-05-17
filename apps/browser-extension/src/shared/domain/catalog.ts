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

export async function callRawTool(
  name: string,
  args: Record<string, unknown>,
  expectMutation = false,
) {
  return sendBackgroundRequest(
    {
      type: 'tool/call',
      payload: { name, args, expectMutation },
    },
    z.unknown(),
  );
}
