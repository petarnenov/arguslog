/**
 * Parses the dashboard URL into a `PageContext` shape that the side panel uses to pre-fill
 * workspace selection (org / project / issue / release).
 *
 * Dashboard route shape (per `apps/web/src/router.tsx`):
 *   /orgs/{slug}/projects/{id}                       — project home
 *   /orgs/{slug}/projects/{id}/issues/{id}           — issue detail
 *   /orgs/{slug}/projects/{id}/releases/{version}    — release detail
 *
 * Issue + release segments are mutually exclusive within a single URL — the inner
 * (?:…|…) alternation makes that explicit and prevents accidentally matching nonsense
 * like /issues/foo/releases/bar.
 *
 * Versions on the release route are operator-typed strings (e.g. `v1.2.3`, `2026.05.17`,
 * git short SHAs), so we capture as a string — never coerce to Number.
 *
 * Extracted into a shared module so the unit test can exercise it without booting the
 * content-script entrypoint (`defineContentScript` is a WXT-only global that doesn't
 * exist in the vitest jsdom environment).
 */
export function parseArgusContext(url: URL) {
  const match =
    /^\/orgs\/([^/]+)\/projects\/(\d+)(?:\/(?:issues\/(\d+)|releases\/([^/?#]+)))?/.exec(
      url.pathname,
    );
  if (!match) {
    return undefined;
  }

  return {
    orgSlug: match[1],
    projectId: Number(match[2]),
    issueId: match[3] ? Number(match[3]) : undefined,
    releaseVersion: match[4],
    sourceTabUrl: url.toString(),
    capturedAt: new Date().toISOString(),
  };
}
