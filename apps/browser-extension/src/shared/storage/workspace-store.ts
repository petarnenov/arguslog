import browser from 'webextension-polyfill';

import {
  PageContextSchema,
  WorkspaceSelectionSchema,
  type PageContext,
  type WorkspaceSelection,
} from '../validation/models';

const WORKSPACE_KEY = 'workspace.selection';
const PAGE_CONTEXT_KEY = 'workspace.pageContext';
const WORKFLOW_STATE_KEY = 'workspace.workflowState';

export async function getWorkspaceSelection(): Promise<WorkspaceSelection> {
  const raw = (await browser.storage.local.get(WORKSPACE_KEY))[WORKSPACE_KEY];
  const parsed = WorkspaceSelectionSchema.safeParse(raw);
  return parsed.success ? parsed.data : { recents: [] };
}

export async function setWorkspaceSelection(selection: WorkspaceSelection): Promise<void> {
  await browser.storage.local.set({
    [WORKSPACE_KEY]: WorkspaceSelectionSchema.parse(selection),
  });
}

export async function getPageContext(): Promise<PageContext | undefined> {
  const raw = (await browser.storage.session.get(PAGE_CONTEXT_KEY))[PAGE_CONTEXT_KEY];
  const parsed = PageContextSchema.safeParse(raw);
  return parsed.success ? parsed.data : undefined;
}

export async function setPageContext(context: PageContext): Promise<void> {
  await browser.storage.session.set({ [PAGE_CONTEXT_KEY]: PageContextSchema.parse(context) });
}

export async function getWorkflowState<T>(): Promise<T | undefined> {
  const raw = (await browser.storage.session.get(WORKFLOW_STATE_KEY))[WORKFLOW_STATE_KEY];
  return raw as T | undefined;
}

export async function setWorkflowState<T>(state: T): Promise<void> {
  await browser.storage.session.set({ [WORKFLOW_STATE_KEY]: state });
}
