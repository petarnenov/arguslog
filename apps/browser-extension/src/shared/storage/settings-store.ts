import browser from 'webextension-polyfill';

import { type ExtensionSettings, ExtensionSettingsSchema } from '../validation/models';

import { readVersioned, writeVersioned } from './schema-version';

const SETTINGS_KEY = 'settings';

/**
 * Bump when the on-disk shape changes. The migrations map in {@link getSettings} must
 * have an entry for every prior version that needs a transform; missing entries are
 * treated as no-op (shape unchanged, just a version bump).
 */
const CURRENT_SCHEMA_VERSION = 1;

export const DEFAULT_SETTINGS: ExtensionSettings = {
  endpoint: 'https://mcp.arguslog.org/mcp',
  persistenceMode: 'persistent',
  debug: false,
  theme: 'system',
};

export async function getSettings(): Promise<ExtensionSettings> {
  return readVersioned({
    area: browser.storage.sync as unknown as chrome.storage.StorageArea,
    key: SETTINGS_KEY,
    currentVersion: CURRENT_SCHEMA_VERSION,
    schema: ExtensionSettingsSchema,
    defaults: DEFAULT_SETTINGS,
  });
}

export async function updateSettings(next: Partial<ExtensionSettings>): Promise<ExtensionSettings> {
  const merged = ExtensionSettingsSchema.parse({
    ...(await getSettings()),
    ...next,
  });
  await writeVersioned(
    browser.storage.sync as unknown as chrome.storage.StorageArea,
    SETTINGS_KEY,
    CURRENT_SCHEMA_VERSION,
    merged,
  );
  return merged;
}
