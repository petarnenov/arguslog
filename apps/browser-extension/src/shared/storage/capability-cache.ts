import browser from 'webextension-polyfill';

import { CapabilitySnapshotSchema, type CapabilitySnapshot } from '../validation/models';

const CURRENT_SNAPSHOT_KEY = 'capabilitySnapshot.current';
const SNAPSHOT_INDEX_KEY = 'capabilitySnapshot.byVersion';

export async function getCapabilitySnapshot(): Promise<CapabilitySnapshot | undefined> {
  const raw = (await browser.storage.local.get(CURRENT_SNAPSHOT_KEY))[CURRENT_SNAPSHOT_KEY];
  const parsed = CapabilitySnapshotSchema.safeParse(raw);
  return parsed.success ? parsed.data : undefined;
}

export async function setCapabilitySnapshot(snapshot: CapabilitySnapshot): Promise<void> {
  const existingIndex =
    ((await browser.storage.local.get(SNAPSHOT_INDEX_KEY))[SNAPSHOT_INDEX_KEY] as
      | Record<string, CapabilitySnapshot>
      | undefined) ?? {};

  await browser.storage.local.set({
    [CURRENT_SNAPSHOT_KEY]: snapshot,
    [SNAPSHOT_INDEX_KEY]: {
      ...existingIndex,
      [snapshot.serverVersion]: snapshot,
    },
  });
}

export async function clearCapabilitySnapshot(): Promise<void> {
  await browser.storage.local.remove([CURRENT_SNAPSHOT_KEY, SNAPSHOT_INDEX_KEY]);
}
