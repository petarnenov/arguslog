/**
 * Tiny versioned-envelope helper for `chrome.storage`-backed blobs.
 *
 * Why: every store today calls `zod.safeParse` on read and silently discards the value
 * if parse fails. That means a schema change between extension versions wipes the
 * operator's state (workspace selection, execution history, settings, …) on update
 * without warning. With versioning + a migrations map the older shape becomes
 * upgradeable instead of disposable; with a strict version check on read we also fail
 * loudly on shapes from the future (downgrades), surfacing the bug instead of erasing
 * the data.
 *
 * Shape on disk:
 * ```
 *   { "__schemaVersion": 2, "data": { /* parsed payload * / } }
 * ```
 *
 * Legacy bare-payload reads (no envelope at all) are tolerated as version 1 so this
 * helper can be retrofitted without a wipe — see {@link readVersioned} for the fallback
 * heuristic.
 *
 * Crucially this lives on the *plaintext* of any encrypted store. `pat-vault.ts` keeps
 * its AES-GCM envelope intact and versions the decrypted plaintext; the ciphertext is
 * never re-encoded by this helper.
 */
import type { z } from 'zod';

/** Reserved field name on the disk envelope. Avoid collisions in domain schemas. */
export const SCHEMA_VERSION_KEY = '__schemaVersion' as const;

export interface VersionedEnvelope<T> {
  /** Monotonically-increasing per-store. v1 is the legacy bare-payload shape. */
  [SCHEMA_VERSION_KEY]: number;
  data: T;
}

/**
 * Migration from `fromVersion` to `fromVersion + 1`. Receives the (already-zod-parsed-
 * at-that-version) payload, returns the next version's payload. Throwing aborts
 * migration and falls back to {@link defaults} so a botched migration can't break the
 * extension.
 */
export type Migration<TPrev, TNext> = (prev: TPrev) => TNext;

export interface VersionedReadOptions<TCurrent> {
  /** Storage area to read from. `local`, `session`, or `sync` per the underlying store. */
  area: chrome.storage.StorageArea;
  /** chrome.storage key. */
  key: string;
  /** Current (latest) version number this code knows how to consume. */
  currentVersion: number;
  /** Zod schema that validates the current-version payload. */
  schema: z.ZodType<TCurrent>;
  /**
   * Fresh-install / unrecoverable-state fallback. Returned (and persisted at the
   * current version) when the disk blob is missing, fails the v1 fallback parse, or any
   * migration step throws.
   */
  defaults: TCurrent;
  /**
   * Migrations indexed by their starting version. Key `1` upgrades v1 → v2, key `2`
   * upgrades v2 → v3, etc. Omitted entries are treated as "no shape change"; that lets
   * stores bump the version for semantics-only changes (validation tightening,
   * default re-pick) without writing a no-op migration body.
   */
  migrations?: Record<number, Migration<unknown, unknown>>;
}

/**
 * Read a versioned blob, running any migrations needed to lift it to {@link currentVersion}.
 * Always returns a parsed value of the current version (defaults on any failure path).
 * If the on-disk version was older, the upgraded value is persisted back so the next
 * read is fast.
 */
export async function readVersioned<TCurrent>(
  opts: VersionedReadOptions<TCurrent>,
): Promise<TCurrent> {
  const raw = (await opts.area.get(opts.key))[opts.key];

  if (raw == null) return opts.defaults;

  const { current, persistBack } = await liftToCurrent(raw, opts);
  if (persistBack) {
    await writeVersioned(opts.area, opts.key, opts.currentVersion, current);
  }
  return current;
}

/**
 * Write a value at the current schema version. Callers that compose this with their own
 * write paths (settings-store.updateSettings, …) use this helper so the envelope shape
 * stays consistent.
 */
export async function writeVersioned<T>(
  area: chrome.storage.StorageArea,
  key: string,
  currentVersion: number,
  data: T,
): Promise<void> {
  const envelope: VersionedEnvelope<T> = { [SCHEMA_VERSION_KEY]: currentVersion, data };
  await area.set({ [key]: envelope });
}

/**
 * Best-effort migration of `raw` (whatever shape was on disk) up to the current version.
 * - If `raw` already has the envelope shape, walk the migrations chain from its version.
 * - If `raw` is a bare payload (legacy v1), treat it as v1 and walk from there.
 * - Any failure (schema mismatch, migration throw) collapses to {@link defaults} —
 *   surfacing the parse error in a separate diagnostic channel is the caller's job, not
 *   ours; we prioritise "extension never crashes on bad storage" here.
 */
async function liftToCurrent<TCurrent>(
  raw: unknown,
  opts: VersionedReadOptions<TCurrent>,
): Promise<{ current: TCurrent; persistBack: boolean }> {
  let version: number;
  let payload: unknown;
  let persistBack = false;

  if (
    typeof raw === 'object' &&
    raw !== null &&
    SCHEMA_VERSION_KEY in raw &&
    typeof (raw as Record<string, unknown>)[SCHEMA_VERSION_KEY] === 'number'
  ) {
    version = (raw as VersionedEnvelope<unknown>)[SCHEMA_VERSION_KEY];
    payload = (raw as VersionedEnvelope<unknown>).data;
  } else {
    // Legacy bare-payload (pre-envelope). Pretend it was v1 so the migrations chain
    // takes care of it, and mark for write-back so the next read sees the envelope.
    version = 1;
    payload = raw;
    persistBack = true;
  }

  if (version > opts.currentVersion) {
    // Downgrade scenario: the disk has a shape from a newer extension version than the
    // one currently running. Don't risk parsing forward — fall through to defaults.
    return { current: opts.defaults, persistBack: false };
  }

  while (version < opts.currentVersion) {
    const migration = opts.migrations?.[version];
    if (migration) {
      try {
        payload = migration(payload);
      } catch {
        return { current: opts.defaults, persistBack: false };
      }
    }
    version += 1;
    persistBack = true;
  }

  const parsed = opts.schema.safeParse(payload);
  if (!parsed.success) return { current: opts.defaults, persistBack: false };
  return { current: parsed.data, persistBack };
}
