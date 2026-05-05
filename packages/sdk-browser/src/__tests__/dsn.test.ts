import { describe, expect, it } from 'vitest';

import { InvalidDsnError, parseDsn } from '../dsn.js';

describe('parseDsn', () => {
  it('parses a valid https DSN', () => {
    const dsn = parseDsn('https://abc123@ingest.argus.io/42');
    expect(dsn).toEqual({
      protocol: 'https',
      publicKey: 'abc123',
      host: 'ingest.argus.io',
      projectId: '42',
      ingestUrl: 'https://ingest.argus.io/api/42/events',
    });
  });

  it('parses a valid http DSN (local dev)', () => {
    const dsn = parseDsn('http://key@localhost:8080/1');
    expect(dsn.ingestUrl).toBe('http://localhost:8080/api/1/events');
  });

  it.each([
    'not-a-dsn',
    '',
    'https://argus.io/42',
    'ftp://key@argus.io/42',
    'https://key@argus.io/',
  ])('rejects invalid DSN: %s', (bad) => {
    expect(() => parseDsn(bad)).toThrow(InvalidDsnError);
  });
});
