import { describe, expect, it } from 'vitest';

import { parseIncidents } from '../lib/incidents';

const sample = `# Incidents

Some explanatory prelude.

<!-- INCIDENTS BELOW -->

## 2026-05-13T18:42Z — Worker deploy failed (minor)
Transient Railway builder failure on first attempt. Retry succeeded.
- Affected: worker
- Status: resolved
- Duration: 7 min

## 2026-05-10T03:11Z — Ingest CORS misconfiguration (major)
SDK browser requests were silently rejected from app.arguslog.org origin.
- Affected: ingest, web
- Status: resolved
- Duration: 22 min
`;

describe('parseIncidents', () => {
  it('parses both incidents from a well-formed file', () => {
    const incidents = parseIncidents(sample);
    expect(incidents).toHaveLength(2);
  });

  it('extracts heading metadata correctly', () => {
    const [first] = parseIncidents(sample);
    expect(first?.startedAt).toBe('2026-05-13T18:42Z');
    expect(first?.title).toBe('Worker deploy failed');
    expect(first?.severity).toBe('minor');
  });

  it('extracts the body fields', () => {
    const [, second] = parseIncidents(sample);
    expect(second?.affected).toEqual(['ingest', 'web']);
    expect(second?.status).toBe('resolved');
    expect(second?.duration).toBe('22 min');
    expect(second?.description).toContain('SDK browser requests');
  });

  it('orders incidents newest-first regardless of source order', () => {
    const reversed = `<!-- INCIDENTS BELOW -->\n\n## 2026-01-01T00:00Z — Old (minor)\n\n## 2026-12-31T23:59Z — New (minor)\n`;
    const incidents = parseIncidents(reversed);
    expect(incidents[0]?.title).toBe('New');
    expect(incidents[1]?.title).toBe('Old');
  });

  it('returns an empty list when the file has no incidents', () => {
    const empty = `# Incidents\n\n<!-- INCIDENTS BELOW -->\n`;
    expect(parseIncidents(empty)).toEqual([]);
  });

  it('skips malformed headings', () => {
    const malformed = `<!-- INCIDENTS BELOW -->\n\n## not a real heading\n\n## 2026-05-13T20:00Z — Real (minor)\n`;
    const incidents = parseIncidents(malformed);
    expect(incidents).toHaveLength(1);
    expect(incidents[0]?.title).toBe('Real');
  });
});
