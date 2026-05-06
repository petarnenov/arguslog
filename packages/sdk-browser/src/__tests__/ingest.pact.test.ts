import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { MatchersV3, PactV3 } from '@pact-foundation/pact';
import { afterEach, beforeEach, describe, it } from 'vitest';

import { __resetForTests, captureException, captureMessage, flush, init } from '../index.js';

const { like, regex, integer } = MatchersV3;

const PACT_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../../pacts');
const PROJECT_ID = '101';
const PUBLIC_KEY = 'public-key-active';

function provider(): PactV3 {
  return new PactV3({
    consumer: 'arguslog-sdk-browser',
    provider: 'arguslog-ingest',
    dir: PACT_DIR,
    logLevel: 'warn',
  });
}

function dsnFor(mockServerUrl: string): string {
  // PactV3 returns http://127.0.0.1:<port>; reshape into the DSN the SDK parses
  // back into the same ingest URL the contract pins.
  const u = new URL(mockServerUrl);
  return `${u.protocol}//${PUBLIC_KEY}@${u.host}/${PROJECT_ID}`;
}

describe('arguslog-sdk-browser <-> arguslog-ingest contract', () => {
  beforeEach(() => __resetForTests());
  afterEach(() => __resetForTests());

  // The trailing setTimeout below is a workaround for a real SDK bug: client.flush()
  // does not await an in-flight transport.flush() (Transport.flush returns early when
  // already flushing). Tracked in the SDK; remove the delays once the SDK awaits the
  // pending send promise instead of fire-and-forgetting.

  it('captureException(TypeError) → POST /api/{projectId}/events with X-Argus-Auth + EventPayload, expect 202', async () => {
    const p = provider()
      .uponReceiving('a TypeError captured by the browser SDK')
      .withRequest({
        method: 'POST',
        path: `/api/${PROJECT_ID}/events`,
        headers: {
          'Content-Type': 'application/json',
          'X-Argus-Auth': `Argus DSN ${PUBLIC_KEY}`,
        },
        body: like({
          eventId: regex('^[0-9a-f]{32}$', 'aabbccddeeff00112233445566778899'),
          timestamp: integer(1730000000000),
          platform: 'javascript',
          sdk: { name: 'arguslog.javascript', version: like('0.0.0') },
          level: 'error',
          breadcrumbs: like([]),
          exception: {
            values: [
              like({
                type: 'TypeError',
                value: 'x is undefined',
                stacktrace: { frames: like([]) },
              }),
            ],
          },
        }),
      })
      .willRespondWith({
        status: 202,
        headers: { 'Content-Type': 'application/json' },
        body: {
          eventId: regex(
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
            '12a827e7-f71e-4fcd-9ebf-c6ee9847b10f',
          ),
        },
      });

    await p.executeTest(async (mock) => {
      init({ dsn: dsnFor(mock.url) });
      captureException(new TypeError('x is undefined'));
      await flush();
      await new Promise((r) => setTimeout(r, 200));
    });
  });

  it('captureMessage(warning) → same envelope shape but message-only, expect 202', async () => {
    const p = provider()
      .uponReceiving('a warning message captured by the browser SDK')
      .withRequest({
        method: 'POST',
        path: `/api/${PROJECT_ID}/events`,
        headers: {
          'Content-Type': 'application/json',
          'X-Argus-Auth': `Argus DSN ${PUBLIC_KEY}`,
        },
        body: like({
          eventId: regex('^[0-9a-f]{32}$', 'aabbccddeeff00112233445566778899'),
          timestamp: integer(1730000000000),
          platform: 'javascript',
          sdk: { name: 'arguslog.javascript', version: like('0.0.0') },
          level: 'warning',
          breadcrumbs: like([]),
          message: 'config drift detected',
        }),
      })
      .willRespondWith({
        status: 202,
        headers: { 'Content-Type': 'application/json' },
        body: {
          eventId: regex(
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
            '12a827e7-f71e-4fcd-9ebf-c6ee9847b10f',
          ),
        },
      });

    await p.executeTest(async (mock) => {
      init({ dsn: dsnFor(mock.url) });
      captureMessage('config drift detected', 'warning');
      await flush();
      await new Promise((r) => setTimeout(r, 200));
    });
  });
});
