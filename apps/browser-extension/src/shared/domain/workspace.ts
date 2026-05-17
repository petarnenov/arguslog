import {
  DsnSchema,
  MemberSchema,
  OrgSummarySchema,
  ProjectSummarySchema,
} from '@arguslog/mcp-server/contract';
import { z } from 'zod';

import { sendBackgroundRequest } from '../utils/messaging';
import { WorkspaceSelectionSchema, PageContextSchema } from '../validation/models';

import { callRawTool } from './catalog';

export async function listMyOrgs() {
  return z.array(OrgSummarySchema).parse(await callRawTool('list_my_orgs', {}));
}

export async function listProjects(orgId: number) {
  return z.array(ProjectSummarySchema).parse(await callRawTool('list_projects', { orgId }));
}

export async function listMembers(orgId: number) {
  return z.array(MemberSchema).parse(await callRawTool('list_members', { orgId }));
}

export async function listDsns(projectId: number) {
  return z.array(DsnSchema).parse(await callRawTool('list_dsns', { projectId }));
}

export async function getWorkspaceSelection() {
  return sendBackgroundRequest({ type: 'workspace/get' }, WorkspaceSelectionSchema);
}

export async function updateWorkspaceSelection(
  selection: z.infer<typeof WorkspaceSelectionSchema>,
) {
  return sendBackgroundRequest(
    {
      type: 'workspace/set',
      payload: selection,
    },
    WorkspaceSelectionSchema,
  );
}

export async function getPageContext() {
  return sendBackgroundRequest({ type: 'page-context/get' }, PageContextSchema.optional());
}
