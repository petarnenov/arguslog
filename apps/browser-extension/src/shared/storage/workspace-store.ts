import browser from 'webextension-polyfill';

import {
  PageContextSchema,
  WorkspaceSelectionSchema,
  type PageContext,
  type WorkspaceSelection,
} from '../validation/models';

import { readVersioned, writeVersioned } from './schema-version';

const WORKSPACE_KEY = 'workspace.selection';
const PAGE_CONTEXT_KEY = 'workspace.pageContext';
const WORKFLOW_STATE_KEY = 'workspace.workflowState';

const WORKSPACE_SCHEMA_VERSION = 1;

const DEFAULT_WORKSPACE_SELECTION: WorkspaceSelection = { recents: [] };

export async function getWorkspaceSelection(): Promise<WorkspaceSelection> {
  return readVersioned({
    area: browser.storage.local as unknown as chrome.storage.StorageArea,
    key: WORKSPACE_KEY,
    currentVersion: WORKSPACE_SCHEMA_VERSION,
    schema: WorkspaceSelectionSchema,
    defaults: DEFAULT_WORKSPACE_SELECTION,
  });
}

export async function setWorkspaceSelection(selection: WorkspaceSelection): Promise<void> {
  await writeVersioned(
    browser.storage.local as unknown as chrome.storage.StorageArea,
    WORKSPACE_KEY,
    WORKSPACE_SCHEMA_VERSION,
    WorkspaceSelectionSchema.parse(selection),
  );
}

// PageContext is session-scoped AND auto-republished by the content script on every
// arguslog.org tab load — version migration would be wasted ceremony. The existing
// safeParse-or-undefined path self-heals on shape drift the moment the operator
// reloads any arguslog tab.
export async function getPageContext(): Promise<PageContext | undefined> {
  const raw = (await browser.storage.session.get(PAGE_CONTEXT_KEY))[PAGE_CONTEXT_KEY];
  const parsed = PageContextSchema.safeParse(raw);
  return parsed.success ? parsed.data : undefined;
}

export async function setPageContext(context: PageContext): Promise<void> {
  await browser.storage.session.set({ [PAGE_CONTEXT_KEY]: PageContextSchema.parse(context) });
}

// Workflow state is an arbitrary-T scratchpad used by the in-flight workflow runner.
// The shape is owned by individual workflows, not this module, so versioning here would
// only get in the way. Session-scoped and discarded on browser restart anyway.
export async function getWorkflowState<T>(): Promise<T | undefined> {
  const raw = (await browser.storage.session.get(WORKFLOW_STATE_KEY))[WORKFLOW_STATE_KEY];
  return raw as T | undefined;
}

export async function setWorkflowState<T>(state: T): Promise<void> {
  await browser.storage.session.set({ [WORKFLOW_STATE_KEY]: state });
}

export async function clearWorkflowState(): Promise<void> {
  await browser.storage.session.remove(WORKFLOW_STATE_KEY);
}
