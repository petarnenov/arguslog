import { apiFetch } from './client';

/**
 * Dashboard view of one Slack workspace install. Mirrors `SlackWorkspaceDto` on the API side —
 * the bot install token is intentionally omitted from this shape because the response never
 * includes it.
 */
export interface SlackWorkspace {
  id: number;
  slackTeamId: string;
  slackTeamName: string;
  orgId: number;
  defaultProjectId: number | null;
  installedByUserId: string | null;
  installedAt: string;
  deactivatedAt: string | null;
  active: boolean;
}

export function listSlackWorkspaces(orgId: number): Promise<SlackWorkspace[]> {
  return apiFetch<SlackWorkspace[]>(`/api/v1/orgs/${orgId}/integrations/slack/workspaces`);
}

export function deleteSlackWorkspace(orgId: number, id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/orgs/${orgId}/integrations/slack/workspaces/${id}`, {
    method: 'DELETE',
  });
}

export function setSlackDefaultProject(
  orgId: number,
  id: number,
  defaultProjectId: number | null,
): Promise<SlackWorkspace> {
  return apiFetch<SlackWorkspace>(`/api/v1/orgs/${orgId}/integrations/slack/workspaces/${id}`, {
    method: 'PATCH',
    body: JSON.stringify({ defaultProjectId }),
  });
}

export interface SlackInstallStart {
  authorizeUrl: string;
}

/**
 * Initiates a Slack OAuth install for the given org. The api returns Slack's authorize URL
 * (with a server-signed state token bound to the current user + org); the caller then does a
 * top-level navigation to that URL. We can't have the api itself 302 directly to Slack — the
 * dashboard and api live on different origins, so a link-click navigation would lose the JWT
 * and hit 401 before the controller could issue the redirect.
 */
export function startSlackInstall(orgId: number): Promise<SlackInstallStart> {
  return apiFetch<SlackInstallStart>(
    `/api/v1/orgs/${orgId}/integrations/slack/oauth/install`,
  );
}
