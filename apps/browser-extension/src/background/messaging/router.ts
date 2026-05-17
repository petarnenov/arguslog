import {
  getCapabilitySnapshot,
  setCapabilitySnapshot,
  clearCapabilitySnapshot,
} from '../../shared/storage/capability-cache';
import {
  clearExecutionHistory,
  getExecutionHistory,
} from '../../shared/storage/execution-history';
import { getSettings, updateSettings } from '../../shared/storage/settings-store';
import {
  getPageContext,
  getWorkspaceSelection,
  setPageContext,
  setWorkspaceSelection,
} from '../../shared/storage/workspace-store';
import { createAppError } from '../../shared/types/errors';
import { BackgroundRequestSchema } from '../../shared/types/messages';
import { BackgroundEnvelopeSchema, type ExtensionSettings } from '../../shared/validation/models';
import { clearPat, getAuthSession, getPat, savePat } from '../auth/pat-vault';
import { createDiagnosticBundle } from '../diagnostics/log-buffer';
import {
  callTool,
  connectAndSnapshot,
  getPrompt,
  listPrompts,
  listTools,
} from '../transport/mcp-transport';

async function requirePat(): Promise<string> {
  const pat = await getPat();
  if (!pat) {
    throw createAppError('NO_PAT', 'No PAT stored. Connect first.');
  }
  return pat;
}

function ok(data: unknown) {
  return BackgroundEnvelopeSchema.parse({ ok: true, data });
}

function fail(error: unknown) {
  const parsed =
    typeof error === 'object' && error && 'bucket' in error
      ? error
      : createAppError(
          'SERVER_UNAVAILABLE',
          error instanceof Error ? error.message : String(error),
        );

  return BackgroundEnvelopeSchema.parse({ ok: false, error: parsed });
}

export async function handleBackgroundRequest(rawMessage: unknown): Promise<unknown> {
  try {
    const message = BackgroundRequestSchema.parse(rawMessage);
    const settings = await getSettings();

    switch (message.type) {
      case 'settings/get':
        return ok(settings);
      case 'settings/update': {
        const nextPayload: Partial<ExtensionSettings> = {};
        if (message.payload.endpoint !== undefined) nextPayload.endpoint = message.payload.endpoint;
        if (message.payload.persistenceMode !== undefined) {
          nextPayload.persistenceMode = message.payload.persistenceMode;
        }
        if (message.payload.debug !== undefined) nextPayload.debug = message.payload.debug;
        if (message.payload.theme !== undefined) nextPayload.theme = message.payload.theme;
        const next = await updateSettings(nextPayload);
        if (
          message.payload.persistenceMode &&
          message.payload.persistenceMode !== settings.persistenceMode
        ) {
          const authSession = await getAuthSession();
          const pat = await getPat();
          if (authSession?.accountSummary && pat) {
            await savePat(pat, next, authSession.accountSummary);
          }
        }
        return ok(next);
      }
      case 'connection/status': {
        const authSession = await getAuthSession();
        const capabilitySnapshot = await getCapabilitySnapshot();
        const pageContext = await getPageContext();
        const workspaceSelection = await getWorkspaceSelection();
        return ok({
          settings,
          authSession: authSession ?? {
            patPresent: false,
            persistenceMode: settings.persistenceMode,
          },
          capabilitySnapshot,
          pageContext,
          workspaceSelection,
        });
      }
      case 'connection/connect': {
        const nextSettings = await updateSettings({
          endpoint: message.payload.endpoint ?? settings.endpoint,
          persistenceMode: message.payload.persistenceMode ?? settings.persistenceMode,
          debug: message.payload.debug ?? settings.debug,
        });
        const { accountSummary, snapshot } = await connectAndSnapshot({
          endpoint: nextSettings.endpoint,
          pat: message.payload.pat,
        });
        const authSession = await savePat(message.payload.pat, nextSettings, accountSummary);
        await setCapabilitySnapshot(snapshot);
        return ok({
          settings: nextSettings,
          authSession,
          capabilitySnapshot: snapshot,
        });
      }
      case 'connection/disconnect':
        await clearPat();
        await clearCapabilitySnapshot();
        return ok({ success: true });
      case 'catalog/refresh': {
        const pat = await requirePat();
        const { snapshot } = await connectAndSnapshot({ endpoint: settings.endpoint, pat });
        await setCapabilitySnapshot(snapshot);
        return ok(snapshot);
      }
      case 'catalog/tools': {
        const pat = await requirePat();
        const tools = await listTools({ endpoint: settings.endpoint, pat });
        return ok(tools);
      }
      case 'catalog/prompts': {
        const pat = await requirePat();
        const prompts = await listPrompts({ endpoint: settings.endpoint, pat });
        return ok(prompts);
      }
      case 'prompt/get': {
        const pat = await requirePat();
        const prompt = await getPrompt(
          { endpoint: settings.endpoint, pat },
          message.payload.name,
          message.payload.arguments,
        );
        return ok(prompt);
      }
      case 'tool/call': {
        const pat = await requirePat();
        const snapshot = await getCapabilitySnapshot();
        if (snapshot && !snapshot.toolNames.includes(message.payload.name)) {
          throw createAppError(
            'TOOL_MISSING',
            `Tool "${message.payload.name}" is not present in the current capability snapshot.`,
          );
        }
        const result = await callTool(
          { endpoint: settings.endpoint, pat },
          message.payload.name,
          message.payload.args,
          message.payload.expectMutation,
        );
        return ok(result);
      }
      case 'workspace/get':
        return ok(await getWorkspaceSelection());
      case 'workspace/set':
        await setWorkspaceSelection(message.payload);
        return ok(message.payload);
      case 'page-context/get':
        return ok(await getPageContext());
      case 'page-context/publish':
        await setPageContext(message.payload);
        return ok(message.payload);
      case 'diagnostics/export': {
        const authSession = (await getAuthSession()) ?? {
          patPresent: false,
          persistenceMode: settings.persistenceMode,
        };
        const snapshot = await getCapabilitySnapshot();
        const bundle = createDiagnosticBundle({
          settings,
          authSession,
          ...(snapshot ? { capabilitySnapshot: snapshot } : {}),
        });
        return ok(bundle);
      }
      case 'sidepanel/open': {
        const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
        const tab = tabs[0];
        if (!tab?.windowId || !chrome.sidePanel?.open) {
          throw createAppError('SERVER_UNAVAILABLE', 'Side panel API is unavailable.');
        }
        await chrome.sidePanel.open({ windowId: tab.windowId });
        return ok({ success: true });
      }
      case 'execution-history/get':
        return ok(await getExecutionHistory());
      case 'execution-history/clear':
        await clearExecutionHistory();
        return ok({ cleared: true });
      default:
        throw createAppError('VALIDATION_ERROR', 'Unsupported background message.');
    }
  } catch (error) {
    return fail(error);
  }
}
