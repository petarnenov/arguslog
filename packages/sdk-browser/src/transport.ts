import type { EventPayload, ParsedDsn } from './types.js';

export interface TransportOptions {
  fetch?: typeof fetch;
  maxRetries?: number;
  baseDelayMs?: number;
}

export class Transport {
  private readonly fetchImpl: typeof fetch;
  private readonly maxRetries: number;
  private readonly baseDelayMs: number;
  private readonly queue: EventPayload[] = [];
  private flushing = false;

  constructor(
    private readonly dsn: ParsedDsn,
    private readonly publicKey: string,
    opts: TransportOptions = {},
  ) {
    this.fetchImpl = opts.fetch ?? globalThis.fetch.bind(globalThis);
    this.maxRetries = opts.maxRetries ?? 3;
    this.baseDelayMs = opts.baseDelayMs ?? 200;
  }

  enqueue(event: EventPayload): void {
    this.queue.push(event);
    void this.flush();
  }

  async flush(): Promise<void> {
    if (this.flushing) return;
    this.flushing = true;
    try {
      while (this.queue.length > 0) {
        const event = this.queue.shift()!;
        await this.send(event);
      }
    } finally {
      this.flushing = false;
    }
  }

  private async send(event: EventPayload, attempt = 0): Promise<void> {
    try {
      const response = await this.fetchImpl(this.dsn.ingestUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Argus-Auth': `Argus DSN ${this.publicKey}`,
        },
        body: JSON.stringify(event),
        keepalive: true,
      });
      if (response.status === 429 || response.status >= 500) {
        throw new RetriableError(`HTTP ${response.status}`);
      }
    } catch (err) {
      if (err instanceof RetriableError && attempt < this.maxRetries) {
        const delay = this.baseDelayMs * 2 ** attempt;
        await sleep(delay);
        return this.send(event, attempt + 1);
      }
      // swallow — never let SDK errors crash host app
    }
  }
}

class RetriableError extends Error {}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
