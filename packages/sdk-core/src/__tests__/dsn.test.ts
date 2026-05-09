import { describe, expect, it } from 'vitest';

import { InvalidDsnError, parseDsn } from '../dsn.js';

describe('parseDsn', () => {
  it('parses a production DSN and picks https transport for non-loopback hosts', () => {
    const dsn = parseDsn('arguslog://abc123@ingest.arguslog.io/api/42');
    expect(dsn).toEqual({
      protocol: 'https',
      publicKey: 'abc123',
      host: 'ingest.arguslog.io',
      projectId: '42',
      ingestUrl: 'https://ingest.arguslog.io/api/42/events',
    });
  });

  it('picks http transport for localhost', () => {
    const dsn = parseDsn('arguslog://key@localhost:8080/api/1');
    expect(dsn.protocol).toBe('http');
    expect(dsn.ingestUrl).toBe('http://localhost:8080/api/1/events');
  });

  it('picks http transport for 127.0.0.1', () => {
    expect(parseDsn('arguslog://k@127.0.0.1:8080/api/1').protocol).toBe('http');
  });

  it.each([
    'arguslog://k@192.168.0.186:8080/api/1',
    'arguslog://k@192.168.1.1:8080/api/1',
    'arguslog://k@10.0.0.5:8080/api/1',
    'arguslog://k@10.255.255.255:8080/api/1',
    'arguslog://k@172.16.0.1:8080/api/1',
    'arguslog://k@172.31.255.255:8080/api/1',
  ])('picks http transport for RFC1918 private host %s', (dsn) => {
    expect(parseDsn(dsn).protocol).toBe('http');
  });

  it.each([
    // Just outside RFC1918 — must stay https.
    'arguslog://k@172.15.0.1:8080/api/1', // below 172.16
    'arguslog://k@172.32.0.1:8080/api/1', // above 172.31
    'arguslog://k@193.168.0.1:8080/api/1', // 193, not 192
    'arguslog://k@11.0.0.1:8080/api/1', // not 10
  ])('keeps https transport for public IPs %s', (dsn) => {
    expect(parseDsn(dsn).protocol).toBe('https');
  });

  it.each([
    'not-a-dsn',
    '',
    // missing public key
    'arguslog://@arguslog.io/api/42',
    // wrong scheme
    'https://key@arguslog.io/api/42',
    // wrong path prefix
    'arguslog://key@arguslog.io/42',
    // empty project id
    'arguslog://key@arguslog.io/api/',
  ])('rejects invalid DSN: %s', (bad) => {
    expect(() => parseDsn(bad)).toThrow(InvalidDsnError);
  });
});
