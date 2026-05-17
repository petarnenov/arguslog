import { z } from 'zod';

import { ExtensionSettingsSchema, PageContextSchema, WorkspaceSelectionSchema } from '../validation/models';

export const BackgroundRequestSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('settings/get') }),
  z.object({
    type: z.literal('settings/update'),
    payload: ExtensionSettingsSchema.partial(),
  }),
  z.object({ type: z.literal('connection/status') }),
  z.object({
    type: z.literal('connection/connect'),
    payload: z.object({
      pat: z.string().min(1),
      endpoint: z.string().url().optional(),
      persistenceMode: z.enum(['persistent', 'session']).optional(),
      debug: z.boolean().optional(),
    }),
  }),
  z.object({ type: z.literal('connection/disconnect') }),
  z.object({ type: z.literal('catalog/refresh') }),
  z.object({ type: z.literal('catalog/tools') }),
  z.object({ type: z.literal('catalog/prompts') }),
  z.object({
    type: z.literal('prompt/get'),
    payload: z.object({
      name: z.string(),
      arguments: z.record(z.string()).default({}),
    }),
  }),
  z.object({
    type: z.literal('tool/call'),
    payload: z.object({
      name: z.string(),
      args: z.record(z.unknown()).default({}),
      expectMutation: z.boolean().default(false),
    }),
  }),
  z.object({ type: z.literal('workspace/get') }),
  z.object({
    type: z.literal('workspace/set'),
    payload: WorkspaceSelectionSchema,
  }),
  z.object({ type: z.literal('page-context/get') }),
  z.object({
    type: z.literal('page-context/publish'),
    payload: PageContextSchema,
  }),
  z.object({ type: z.literal('diagnostics/export') }),
  z.object({ type: z.literal('sidepanel/open') }),
]);

export type BackgroundRequest = z.infer<typeof BackgroundRequestSchema>;
