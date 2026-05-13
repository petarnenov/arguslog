import { describe, expect, it } from 'vitest';

import { overallStatus, type ProbeResult } from '../lib/healthChecks';

function result(id: string, status: 'up' | 'down' | 'unknown'): ProbeResult {
  return { id, status, latencyMs: 10, checkedAt: '2026-05-13T20:00:00Z' };
}

describe('overallStatus', () => {
  it('returns unknown for an empty input', () => {
    expect(overallStatus([])).toBe('unknown');
  });

  it('returns operational when every service is up', () => {
    expect(overallStatus([result('a', 'up'), result('b', 'up'), result('c', 'up')])).toBe(
      'operational',
    );
  });

  it('returns outage when every service is down', () => {
    expect(overallStatus([result('a', 'down'), result('b', 'down')])).toBe('outage');
  });

  it('returns degraded when at least one but not all are down', () => {
    expect(overallStatus([result('a', 'up'), result('b', 'down')])).toBe('degraded');
  });

  it('treats unknown as neither up nor down → degraded when mixed with up', () => {
    expect(overallStatus([result('a', 'up'), result('b', 'unknown')])).toBe('degraded');
  });
});
