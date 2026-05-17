import { describe, expect, it } from 'vitest';

import { parseArgusContext } from '../../src/shared/utils/parse-page-context';

describe('parseArgusContext', () => {
  it('captures org + project on the bare project route', () => {
    const ctx = parseArgusContext(new URL('https://app.arguslog.org/orgs/acme/projects/42'));
    expect(ctx).toBeDefined();
    expect(ctx?.orgSlug).toBe('acme');
    expect(ctx?.projectId).toBe(42);
    expect(ctx?.issueId).toBeUndefined();
    expect(ctx?.releaseVersion).toBeUndefined();
  });

  it('captures issueId on the issue-detail route', () => {
    const ctx = parseArgusContext(
      new URL('https://app.arguslog.org/orgs/acme/projects/42/issues/7'),
    );
    expect(ctx?.issueId).toBe(7);
    expect(ctx?.releaseVersion).toBeUndefined();
  });

  it('captures releaseVersion on the release-detail route', () => {
    const ctx = parseArgusContext(
      new URL('https://app.arguslog.org/orgs/acme/projects/42/releases/v1.2.3'),
    );
    expect(ctx?.releaseVersion).toBe('v1.2.3');
    expect(ctx?.issueId).toBeUndefined();
  });

  it('preserves complex version identifiers (date, SHA) verbatim', () => {
    const date = parseArgusContext(
      new URL('https://app.arguslog.org/orgs/acme/projects/42/releases/2026.05.17'),
    );
    expect(date?.releaseVersion).toBe('2026.05.17');

    const sha = parseArgusContext(
      new URL('https://app.arguslog.org/orgs/acme/projects/42/releases/a1b2c3d'),
    );
    expect(sha?.releaseVersion).toBe('a1b2c3d');
  });

  it('returns undefined for the legacy singular `/org/.../project/` URL (bug fix regression guard)', () => {
    const ctx = parseArgusContext(new URL('https://app.arguslog.org/org/acme/project/42'));
    expect(ctx).toBeUndefined();
  });

  it('returns undefined for a non-dashboard URL', () => {
    expect(parseArgusContext(new URL('https://app.arguslog.org/'))).toBeUndefined();
    expect(parseArgusContext(new URL('https://app.arguslog.org/settings'))).toBeUndefined();
  });

  it('does not pick up nonsense like /issues/foo or /releases/bar without proper structure', () => {
    // Matches the project prefix but stops at the project — issueId is missing because
    // 'foo' is not a number, and the inner alternation doesn't try to match a string as an
    // issueId. releaseVersion stays undefined because the path doesn't start with /releases.
    const ctx = parseArgusContext(
      new URL('https://app.arguslog.org/orgs/acme/projects/42/issues/foo'),
    );
    expect(ctx?.projectId).toBe(42);
    expect(ctx?.issueId).toBeUndefined();
    expect(ctx?.releaseVersion).toBeUndefined();
  });
});
