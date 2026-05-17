import { describe, expect, it } from 'vitest';

import {
  buildIssueUrl,
  buildProjectUrl,
  buildReleaseUrl,
  getDashboardBaseUrl,
} from '../../src/shared/utils/dashboard-url';

describe('getDashboardBaseUrl', () => {
  it('swaps mcp. → app. on the production endpoint', () => {
    expect(getDashboardBaseUrl('https://mcp.arguslog.org/mcp')).toBe('https://app.arguslog.org');
  });

  it('strips /mcp from self-hosted single-host endpoints', () => {
    expect(getDashboardBaseUrl('https://my-instance.com/mcp')).toBe('https://my-instance.com');
  });

  it('handles localhost dev endpoints (port preserved)', () => {
    expect(getDashboardBaseUrl('http://localhost:8081/mcp')).toBe('http://localhost:8081');
  });

  it('leaves endpoints already lacking /mcp untouched (beyond the host swap)', () => {
    expect(getDashboardBaseUrl('https://mcp.arguslog.org')).toBe('https://app.arguslog.org');
    expect(getDashboardBaseUrl('https://my-instance.com')).toBe('https://my-instance.com');
  });

  it('falls back gracefully on malformed input', () => {
    // Not a real URL; helper shouldn't throw. Returns the trimmed input verbatim.
    expect(getDashboardBaseUrl('not-a-url/mcp')).toBe('not-a-url');
  });
});

describe('builders', () => {
  const base = 'https://app.arguslog.org';

  it('buildProjectUrl', () => {
    expect(buildProjectUrl(base, 'acme', 42)).toBe(
      'https://app.arguslog.org/orgs/acme/projects/42',
    );
  });

  it('buildIssueUrl', () => {
    expect(buildIssueUrl(base, 'acme', 42, 7)).toBe(
      'https://app.arguslog.org/orgs/acme/projects/42/issues/7',
    );
  });

  it('buildReleaseUrl URL-encodes versions with reserved chars', () => {
    expect(buildReleaseUrl(base, 'acme', 42, 'v1.2.3')).toBe(
      'https://app.arguslog.org/orgs/acme/projects/42/releases/v1.2.3',
    );
    expect(buildReleaseUrl(base, 'acme', 42, 'feat/v1')).toBe(
      'https://app.arguslog.org/orgs/acme/projects/42/releases/feat%2Fv1',
    );
  });

  it('URL-encodes orgSlug too (paranoia — slugs are normally safe)', () => {
    expect(buildProjectUrl(base, 'with space', 42)).toBe(
      'https://app.arguslog.org/orgs/with%20space/projects/42',
    );
  });
});
