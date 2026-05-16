import { apiFetch } from './client';

export type DestinationKind = 'telegram' | 'email' | 'slack' | 'webhook' | 'github_issue';

export interface AlertDestination {
  id: number;
  orgId: number;
  kind: DestinationKind;
  name: string;
  /**
   * Generic on/off toggle (V40). Disabled destinations stay in the table but are skipped by the
   * worker dispatcher, so the operator can pause a Slack-spam channel or freeze auto-triage
   * without losing the encrypted config / token.
   */
  enabled: boolean;
  createdAt: string;
}

export type AlertLevel = 'fatal' | 'error' | 'warning' | 'info' | 'debug';

/** Mirrors `AlertRuleConditions` on the api. All clauses optional; {} = "always match". */
export interface AlertRuleConditions {
  level?: { in: AlertLevel[] };
  firstSeenWindow?: string; // ISO-8601 duration (e.g. PT5M, PT2H, P1D)
  occurrenceThreshold?: number;
  tag?: { key: string; in: string[] };
}

export interface AlertRuleActions {
  destinationIds: number[];
}

export interface AlertRule {
  id: number;
  projectId: number;
  name: string;
  conditions: AlertRuleConditions;
  actions: AlertRuleActions;
  throttleSeconds: number;
  enabled: boolean;
  createdAt: string;
}

// ── destinations ──────────────────────────────────────────────────────────

export function listAlertDestinations(orgId: number): Promise<AlertDestination[]> {
  return apiFetch<AlertDestination[]>(`/api/v1/orgs/${orgId}/alert-destinations`);
}

export function createAlertDestination(
  orgId: number,
  body: { kind: DestinationKind; name: string; config: unknown },
): Promise<AlertDestination> {
  return apiFetch<AlertDestination>(`/api/v1/orgs/${orgId}/alert-destinations`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateAlertDestination(
  orgId: number,
  id: number,
  body: { kind: DestinationKind; name: string; config: unknown },
): Promise<AlertDestination> {
  return apiFetch<AlertDestination>(`/api/v1/orgs/${orgId}/alert-destinations/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export function deleteAlertDestination(orgId: number, id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/orgs/${orgId}/alert-destinations/${id}`, { method: 'DELETE' });
}

/**
 * Flip the on/off toggle on a destination. Dedicated endpoint (not overloaded onto PUT) so the
 * dashboard's pause switch doesn't have to re-supply the encrypted config blob it never sees.
 */
export function setAlertDestinationEnabled(
  orgId: number,
  id: number,
  enabled: boolean,
): Promise<AlertDestination> {
  return apiFetch<AlertDestination>(
    `/api/v1/orgs/${orgId}/alert-destinations/${id}/enabled`,
    {
      method: 'PATCH',
      body: JSON.stringify({ enabled }),
    },
  );
}

// ── rules ─────────────────────────────────────────────────────────────────

export function listAlertRules(projectId: number): Promise<AlertRule[]> {
  return apiFetch<AlertRule[]>(`/api/v1/projects/${projectId}/alert-rules`);
}

export interface AlertRuleWriteBody {
  name: string;
  conditions: AlertRuleConditions;
  actions: AlertRuleActions;
  throttleSeconds: number;
  enabled: boolean;
}

export function createAlertRule(projectId: number, body: AlertRuleWriteBody): Promise<AlertRule> {
  return apiFetch<AlertRule>(`/api/v1/projects/${projectId}/alert-rules`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateAlertRule(
  projectId: number,
  id: number,
  body: AlertRuleWriteBody,
): Promise<AlertRule> {
  return apiFetch<AlertRule>(`/api/v1/projects/${projectId}/alert-rules/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export function deleteAlertRule(projectId: number, id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${projectId}/alert-rules/${id}`, { method: 'DELETE' });
}
