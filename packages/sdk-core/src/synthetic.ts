import type { EventPayload, Level } from './types.js';
import { SDK_VERSION } from './version.generated.js';

/**
 * Build a realistic event payload for connectivity testing — what the Connect-Project wizard
 * fires when the user clicks "Test ping", what `arguslog ping` ships from the CLI, and what
 * the MCP server's `send_test_event` tool delivers on behalf of an AI agent.
 *
 * Shape choices:
 *   - {@link EventPayload.exception} (not {@code message}) so the event hits the full
 *     fingerprinter + symbolicator path, exactly mirroring a real production event.
 *   - {@code tags.synthetic = "true"} so the Issues page filter can find / exclude these.
 *   - Stable type / value so repeated probes deduplicate into one issue per project.
 *
 * The function is intentionally allocator-only — no fetch, no DSN parsing. Callers wrap it
 * in the appropriate transport: the wizard uses {@code fetch} directly, the CLI uses Node's
 * undici, and the MCP server hits Railway's internal ingest URL.
 */
export interface BuildSyntheticEventOptions {
  /** Free-form annotation surfaced as the event value + tag.source. */
  source?: string;
  /** Override the event level. Default {@code error}. */
  level?: Level;
  /** Override the message text shown in the event "value". */
  message?: string;
  /** Override the event timestamp. Default {@code Date.now()}. */
  now?: () => number;
  /** Override the event id. Default a fresh UUID v4 via {@code crypto.randomUUID}. */
  newId?: () => string;
}

export function buildSyntheticEvent(opts: BuildSyntheticEventOptions = {}): EventPayload {
  const now = opts.now ?? Date.now;
  const newId = opts.newId ?? (() => crypto.randomUUID());
  const message =
    opts.message ?? 'Synthetic connectivity test — verifying SDK → ingest wire path.';
  const source = opts.source ?? 'arguslog/test';

  return {
    eventId: newId().replace(/-/g, ''),
    timestamp: now(),
    platform: 'javascript',
    sdk: { name: 'arguslog.synthetic', version: SDK_VERSION },
    level: opts.level ?? 'error',
    exception: {
      values: [
        {
          type: 'ArguslogConnectivityProbe',
          value: message,
          stacktrace: {
            frames: [
              {
                filename: source,
                function: 'buildSyntheticEvent',
                lineno: 1,
                colno: 1,
                inApp: false,
              },
            ],
          },
        },
      ],
    },
    tags: {
      synthetic: 'true',
      source,
    },
  };
}
