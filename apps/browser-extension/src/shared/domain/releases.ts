import { CreateReleaseInputSchema, ReleaseSummarySchema } from '@arguslog/mcp-server/contract';
import { z } from 'zod';

import { callRawTool } from './catalog';

export async function listReleases(projectId: number) {
  return z.array(ReleaseSummarySchema).parse(await callRawTool('list_release', { projectId }));
}

export async function getRelease(projectId: number, id: number) {
  return ReleaseSummarySchema.parse(await callRawTool('get_release', { projectId, id }));
}

export async function createRelease(input: z.input<typeof CreateReleaseInputSchema>) {
  const parsed = CreateReleaseInputSchema.parse(input);
  return ReleaseSummarySchema.parse(await callRawTool('create_release', parsed, true));
}
