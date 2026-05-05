import { apiFetch } from './client';

export type DestinationKind = 'telegram' | 'email' | 'slack' | 'webhook';

export interface AlertDestination {
  id: number;
  orgId: number;
  kind: DestinationKind;
  name: string;
  createdAt: string;
}

export interface AlertRule {
  id: number;
  projectId: number;
  name: string;
  conditions: unknown;
  actions: unknown;
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

// ── rules ─────────────────────────────────────────────────────────────────

export function listAlertRules(projectId: number): Promise<AlertRule[]> {
  return apiFetch<AlertRule[]>(`/api/v1/projects/${projectId}/alert-rules`);
}

export interface AlertRuleWriteBody {
  name: string;
  conditions: unknown;
  actions: unknown;
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
