import type { EventPayload } from '@arguslog/sdk-core';
import { describe, expect, it } from 'vitest';

import { BrowserAdapter } from '../adapter.js';

function emptyEvent(): EventPayload {
  return {
    eventId: 'x',
    timestamp: 0,
    platform: 'javascript',
    sdk: { name: 'arguslog.javascript', version: '0.0.0' },
    level: 'error',
  };
}

describe('BrowserAdapter', () => {
  it('reports javascript platform and sdk identity', () => {
    const a = new BrowserAdapter();
    expect(a.platform).toBe('javascript');
    expect(a.sdkName).toBe('arguslog.javascript');
    // sdkVersion is generator-fed from package.json:version — assert non-empty rather than
    // pinning the literal so the test doesn't have to be re-edited on every release.
    expect(a.sdkVersion).toMatch(/^\d+\.\d+\.\d+/);
  });

  it('enrichEvent attaches the current URL and user agent (jsdom)', () => {
    const a = new BrowserAdapter();
    const ev = emptyEvent();
    a.enrichEvent(ev);
    expect(ev.request?.url).toBeDefined();
    expect(ev.request?.userAgent).toBeDefined();
  });
});
