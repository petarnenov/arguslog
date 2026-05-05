import type { ParsedDsn } from './types.js';

const DSN_RE = /^(https?):\/\/([^@]+)@([^/]+)\/([^/?#]+)$/;

export class InvalidDsnError extends Error {
  constructor(dsn: string) {
    super(`Invalid Argus DSN: ${dsn}`);
    this.name = 'InvalidDsnError';
  }
}

export function parseDsn(dsn: string): ParsedDsn {
  const match = DSN_RE.exec(dsn);
  if (!match) {
    throw new InvalidDsnError(dsn);
  }
  const [, protocol, publicKey, host, projectId] = match;
  if (!protocol || !publicKey || !host || !projectId) {
    throw new InvalidDsnError(dsn);
  }
  if (protocol !== 'http' && protocol !== 'https') {
    throw new InvalidDsnError(dsn);
  }
  const scheme: 'http' | 'https' = protocol;
  return {
    protocol: scheme,
    publicKey,
    host,
    projectId,
    ingestUrl: `${scheme}://${host}/api/${projectId}/events`,
  };
}
