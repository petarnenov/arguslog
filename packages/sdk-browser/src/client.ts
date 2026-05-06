import { BreadcrumbBuffer } from './breadcrumbs.js';
import { parseDsn } from './dsn.js';
import { Scrubber } from './scrubber.js';
import { parseStack } from './stack-parser.js';
import { Transport } from './transport.js';
import type { ArgusOptions, Breadcrumb, EventPayload, Level, ParsedDsn, User } from './types.js';

const SDK_NAME = 'arguslog.javascript';
const SDK_VERSION = '0.0.0';

export class ArgusClient {
  private readonly options: ArgusOptions;
  private readonly dsn: ParsedDsn;
  private readonly transport: Transport;
  private readonly scrubber: Scrubber;
  private readonly breadcrumbs: BreadcrumbBuffer;

  private user: User | undefined;
  private readonly tags: Map<string, string> = new Map();
  private readonly contexts: Map<string, Record<string, unknown>> = new Map();
  private pending: Set<Promise<void>> = new Set();

  constructor(options: ArgusOptions) {
    this.options = options;
    this.dsn = parseDsn(options.dsn);
    this.scrubber = new Scrubber(options.scrubbing);
    this.breadcrumbs = new BreadcrumbBuffer(options.maxBreadcrumbs ?? 50);
    this.transport = new Transport(this.dsn, this.dsn.publicKey, {
      fetch: options.transport?.fetch,
      maxRetries: options.transport?.maxRetries,
    });
  }

  captureException(
    error: unknown,
    hint?: { level?: Level; tags?: Record<string, string> },
  ): string {
    const err = toError(error);
    const event = this.baseEvent(hint?.level ?? 'error');
    event.exception = {
      values: [
        {
          type: err.name || 'Error',
          value: err.message,
          stacktrace: { frames: parseStack(err.stack) },
        },
      ],
    };
    if (hint?.tags) {
      event.tags = { ...(event.tags ?? {}), ...hint.tags };
    }
    return this.dispatch(event);
  }

  captureMessage(message: string, level: Level = 'info'): string {
    const event = this.baseEvent(level);
    event.message = message;
    return this.dispatch(event);
  }

  addBreadcrumb(crumb: Omit<Breadcrumb, 'timestamp'> & { timestamp?: number }): void {
    this.breadcrumbs.add({
      timestamp: crumb.timestamp ?? Date.now(),
      category: crumb.category,
      message: crumb.message,
      level: crumb.level,
      data: crumb.data,
    });
  }

  setUser(user: User | undefined): void {
    this.user = user;
  }

  setTag(key: string, value: string): void {
    this.tags.set(key, value);
  }

  setContext(name: string, ctx: Record<string, unknown>): void {
    this.contexts.set(name, ctx);
  }

  async flush(): Promise<void> {
    while (this.pending.size > 0) {
      const chains = Array.from(this.pending);
      this.pending.clear();
      await Promise.all(chains);
    }
    return this.transport.flush();
  }

  private baseEvent(level: Level): EventPayload {
    const event: EventPayload = {
      eventId: cryptoRandomId(),
      timestamp: Date.now(),
      platform: 'javascript',
      sdk: { name: SDK_NAME, version: SDK_VERSION },
      level,
      release: this.options.release,
      environment: this.options.environment,
      breadcrumbs: this.breadcrumbs.snapshot(),
    };
    if (this.user) event.user = this.user;
    if (this.tags.size > 0) event.tags = Object.fromEntries(this.tags);
    if (this.contexts.size > 0) event.contexts = Object.fromEntries(this.contexts);
    if (typeof window !== 'undefined' && window.location) {
      event.request = {
        url: window.location.href,
        userAgent: window.navigator?.userAgent,
      };
    }
    return event;
  }

  private dispatch(event: EventPayload): string {
    if (!this.shouldSample()) return event.eventId;
    const scrubbed = this.scrubber.scrub(event);
    const chain = this.applyBeforeSend(scrubbed).then((final) => {
      if (final) this.transport.enqueue(final);
    });
    this.pending.add(chain);
    void chain.finally(() => this.pending.delete(chain));
    return event.eventId;
  }

  private shouldSample(): boolean {
    const rate = this.options.sampleRate ?? 1;
    if (rate >= 1) return true;
    if (rate <= 0) return false;
    return Math.random() < rate;
  }

  private async applyBeforeSend(event: EventPayload): Promise<EventPayload | null> {
    if (!this.options.beforeSend) return event;
    try {
      return await this.options.beforeSend(event);
    } catch {
      return event;
    }
  }
}

function toError(value: unknown): Error {
  if (value instanceof Error) return value;
  if (typeof value === 'string') return new Error(value);
  try {
    return new Error(JSON.stringify(value));
  } catch {
    return new Error(String(value));
  }
}

function cryptoRandomId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID().replace(/-/g, '');
  }
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}
