import { CreateProjectInputSchema, CreateProjectResultSchema } from '@arguslog/mcp-server/contract';
import type { input } from 'zod';

import { callRawTool } from './catalog';

export async function createProject(projectInput: input<typeof CreateProjectInputSchema>) {
  const parsed = CreateProjectInputSchema.parse(projectInput);
  return CreateProjectResultSchema.parse(await callRawTool('create_project', parsed, true));
}
