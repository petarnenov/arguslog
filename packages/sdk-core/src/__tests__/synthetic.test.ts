import { describe, expect, it } from 'vitest';

import { buildSyntheticEvent } from '../synthetic';

describe('buildSyntheticEvent', () => {
  it('produces an exception-shaped payload by default', () => {
    const ev = buildSyntheticEvent();
    expect(ev.exception?.values).toHaveLength(1);
    expect(ev.exception?.values[0]?.type).toBe('ArguslogConnectivityProbe');
    expect(ev.message).toBeUndefined();
  });

  it('defaults to level=error so it lands in the live error wall', () => {
    expect(buildSyntheticEvent().level).toBe('error');
  });

  it('stamps the synthetic=true tag so the Issues page filter can find them', () => {
    expect(buildSyntheticEvent().tags?.synthetic).toBe('true');
  });

  it('honors a custom message + level', () => {
    const ev = buildSyntheticEvent({ message: 'CI smoke', level: 'warning' });
    expect(ev.level).toBe('warning');
    expect(ev.exception?.values[0]?.value).toBe('CI smoke');
  });

  it('honors a custom source — surfaced both in stack filename + tag', () => {
    const ev = buildSyntheticEvent({ source: 'connect-wizard' });
    expect(ev.tags?.source).toBe('connect-wizard');
    expect(ev.exception?.values[0]?.stacktrace?.frames[0]?.filename).toBe('connect-wizard');
  });

  it('honors injected timestamp + id (deterministic for tests)', () => {
    const ev = buildSyntheticEvent({
      now: () => 1_704_067_200_000,
      newId: () => 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    });
    expect(ev.timestamp).toBe(1_704_067_200_000);
    // hyphens stripped — ingest accepts both but bare-hex is the canonical form
    expect(ev.eventId).toBe('aaaaaaaabbbbccccddddeeeeeeeeeeee');
  });

  it('includes the sdk identity so the dashboard can label probes distinctly', () => {
    const ev = buildSyntheticEvent();
    expect(ev.sdk.name).toBe('arguslog.synthetic');
    expect(ev.sdk.version).toMatch(/^\d+\.\d+\.\d+/);
  });

  it('stable type makes repeated probes dedupe into one issue per project', () => {
    const a = buildSyntheticEvent();
    const b = buildSyntheticEvent();
    expect(a.exception?.values[0]?.type).toBe(b.exception?.values[0]?.type);
  });
});
