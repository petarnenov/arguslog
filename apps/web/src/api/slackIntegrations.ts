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

/**
 * URL to navigate the browser to in order to start the OAuth install flow. Server-side this
 * 302s to Slack's authorize page with a signed state token bound to the current user + org.
 */
export function slackInstallUrl(orgId: number): string {
  return `/api/v1/orgs/${orgId}/integrations/slack/oauth/install`;
}
