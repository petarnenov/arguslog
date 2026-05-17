/**
 * Derives the dashboard base URL + builds deep links into individual entities.
 *
 * The MCP endpoint and the dashboard share the same root domain but live on different
 * subdomains in production. The Arguslog production convention is:
 *
 *   MCP endpoint    https://mcp.arguslog.org/mcp
 *   Dashboard       https://app.arguslog.org
 *
 * Self-hosters typically don't bother with the `mcp.` → `app.` split — they put both
 * services behind one origin (`https://my-instance.com/mcp` for MCP, the same root for
 * the dashboard). The derivation rule is:
 *
 *   1. Strip the trailing `/mcp` if present (mirror of `endpointBase` in mcp-transport).
 *   2. If the host starts with `mcp.`, swap it for `app.`.
 *   3. Otherwise, return the trimmed base verbatim.
 *
 * The path builders all assume the canonical dashboard route shape:
 *   /orgs/{slug}/projects/{id}                       — project home
 *   /orgs/{slug}/projects/{id}/issues/{id}           — issue detail
 *   /orgs/{slug}/projects/{id}/releases/{version}    — release detail
 *
 * Versions are operator-typed strings, so we URL-encode them at build time — a release
 * tagged `feat/v1` would otherwise collide with the path separator.
 */

export function getDashboardBaseUrl(mcpEndpoint: string): string {
  const trimmed = mcpEndpoint.endsWith('/mcp')
    ? mcpEndpoint.slice(0, -'/mcp'.length)
    : mcpEndpoint;
  try {
    const url = new URL(trimmed);
    if (url.host.startsWith('mcp.')) {
      url.host = 'app.' + url.host.slice('mcp.'.length);
      return url.toString().replace(/\/$/, ''); // URL serialization adds a trailing '/'
    }
  } catch {
    // Bad input — fall through to the verbatim return so the caller still gets *something*.
  }
  return trimmed;
}

export function buildProjectUrl(base: string, orgSlug: string, projectId: number): string {
  return `${base}/orgs/${encodeURIComponent(orgSlug)}/projects/${projectId}`;
}

export function buildIssueUrl(
  base: string,
  orgSlug: string,
  projectId: number,
  issueId: number,
): string {
  return `${buildProjectUrl(base, orgSlug, projectId)}/issues/${issueId}`;
}

export function buildReleaseUrl(
  base: string,
  orgSlug: string,
  projectId: number,
  version: string,
): string {
  return `${buildProjectUrl(base, orgSlug, projectId)}/releases/${encodeURIComponent(version)}`;
}
