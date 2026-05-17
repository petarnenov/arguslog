import { z } from 'zod';

export const McpHealthSchema = z.object({
  ok: z.boolean(),
  service: z.string(),
  version: z.string(),
});

export const McpToolSchema = z.object({
  name: z.string(),
  description: z.string(),
  inputSchema: z.record(z.unknown()),
  outputSchema: z.record(z.unknown()).optional(),
  annotations: z.record(z.unknown()).optional(),
  title: z.string().optional(),
});

export const McpPromptSchema = z.object({
  name: z.string(),
  title: z.string().optional(),
  description: z.string().optional(),
  arguments: z
    .array(
      z.object({
        name: z.string(),
        description: z.string().optional(),
        required: z.boolean().optional(),
      }),
    )
    .optional(),
});

export const McpPromptResultSchema = z.object({
  description: z.string().optional(),
  messages: z.array(
    z.object({
      role: z.enum(['user', 'assistant']),
      content: z.object({
        type: z.literal('text'),
        text: z.string(),
      }),
    }),
  ),
});

export const McpToolCallResultSchema = z.object({
  content: z.array(
    z.object({
      type: z.literal('text'),
      text: z.string(),
    }),
  ),
  structuredContent: z.record(z.unknown()).optional(),
  isError: z.boolean().optional(),
});

export type McpToolDefinition = z.infer<typeof McpToolSchema>;
export type McpPromptDefinition = z.infer<typeof McpPromptSchema>;
