import type { ParsedDsn } from './types.js';

// User-facing DSN format (matches what the api emits in the project-create response and shows in
// the web UI's "Copy DSN" modal): arguslog://<publicKey>@<host>/api/<projectId>
//
// The custom `arguslog://` scheme keeps the DSN string visually distinct from the actual transport
// URL and prevents users from copy-pasting it into a browser hoping it'll open something. The SDK
// derives the real transport scheme from the host: localhost / loopback / RFC1918 → http (dev),
// everything else → https.
const DSN_RE = /^arguslog:\/\/([^@]+)@([^/]+)\/api\/([^/?#]+)$/;

export class InvalidDsnError extends Error {
  constructor(dsn: string) {
    super(`Invalid Arguslog DSN: ${dsn}`);
    this.name = 'InvalidDsnError';
  }
}

export function parseDsn(dsn: string): ParsedDsn {
  const match = DSN_RE.exec(dsn);
  if (!match) {
    throw new InvalidDsnError(dsn);
  }
  const [, publicKey, host, projectId] = match;
  if (!publicKey || !host || !projectId) {
    throw new InvalidDsnError(dsn);
  }
  const protocol: 'http' | 'https' = isLoopback(host) ? 'http' : 'https';
  return {
    protocol,
    publicKey,
    host,
    projectId,
    ingestUrl: `${protocol}://${host}/api/${projectId}/events`,
  };
}

function isLoopback(host: string): boolean {
  // IPv6 literals come bracketed (e.g. [::1]:8080); strip the [..] part. IPv4/hostnames have a
  // single optional :port suffix.
  const bare = host.startsWith('[')
    ? host.slice(0, host.indexOf(']') + 1)
    : (host.split(':')[0] ?? host);
  return (
    bare === 'localhost' ||
    bare.startsWith('127.') ||
    bare === '0.0.0.0' ||
    bare === '[::1]' ||
    bare === '::1'
  );
}
