import { z } from 'zod';

import { sendBackgroundRequest } from '../utils/messaging';
import { ConnectionStatusSchema, ExtensionSettingsSchema } from '../validation/models';

const ConnectResponseSchema = z.object({
  settings: ExtensionSettingsSchema,
  authSession: ConnectionStatusSchema.shape.authSession,
  capabilitySnapshot: ConnectionStatusSchema.shape.capabilitySnapshot,
});

export async function getConnectionStatus() {
  return sendBackgroundRequest({ type: 'connection/status' }, ConnectionStatusSchema);
}

export async function connect(input: {
  pat: string;
  endpoint?: string;
  persistenceMode?: 'persistent' | 'session';
  debug?: boolean;
}) {
  return sendBackgroundRequest(
    {
      type: 'connection/connect',
      payload: input,
    },
    ConnectResponseSchema,
  );
}

export async function disconnect(): Promise<{ success: boolean }> {
  return sendBackgroundRequest(
    { type: 'connection/disconnect' },
    z.object({ success: z.boolean() }),
  );
}

export async function getSettings() {
  return sendBackgroundRequest({ type: 'settings/get' }, ExtensionSettingsSchema);
}

export async function updateSettings(next: Partial<z.infer<typeof ExtensionSettingsSchema>>) {
  return sendBackgroundRequest(
    {
      type: 'settings/update',
      payload: next,
    },
    ExtensionSettingsSchema,
  );
}

export async function refreshCapabilities() {
  return sendBackgroundRequest(
    { type: 'catalog/refresh' },
    ConnectionStatusSchema.shape.capabilitySnapshot.unwrap(),
  );
}

export async function openSidePanel(): Promise<{ success: boolean }> {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });

  if (!tab?.id || !chrome.sidePanel?.open || !chrome.sidePanel?.setOptions) {
    throw new Error('Side panel API is unavailable.');
  }

  await chrome.sidePanel.setOptions({
    tabId: tab.id,
    path: 'sidepanel.html',
    enabled: true,
  });
  await chrome.sidePanel.open({ tabId: tab.id });

  return { success: true };
}
