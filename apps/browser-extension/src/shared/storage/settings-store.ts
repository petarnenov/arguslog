import browser from 'webextension-polyfill';

import { type ExtensionSettings, ExtensionSettingsSchema } from '../validation/models';

const SETTINGS_KEY = 'settings';

export const DEFAULT_SETTINGS: ExtensionSettings = {
  endpoint: 'https://mcp.arguslog.org/mcp',
  persistenceMode: 'persistent',
  debug: false,
  theme: 'system',
};

export async function getSettings(): Promise<ExtensionSettings> {
  const raw = (await browser.storage.sync.get(SETTINGS_KEY))[SETTINGS_KEY];
  const parsed = ExtensionSettingsSchema.safeParse(raw);
  return parsed.success ? parsed.data : DEFAULT_SETTINGS;
}

export async function updateSettings(next: Partial<ExtensionSettings>): Promise<ExtensionSettings> {
  const merged = ExtensionSettingsSchema.parse({
    ...(await getSettings()),
    ...next,
  });
  await browser.storage.sync.set({ [SETTINGS_KEY]: merged });
  return merged;
}
