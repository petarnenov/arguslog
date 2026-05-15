import type { CliConfig } from '../config.js';
import { apiFetch } from '../http.js';

export type AlertLevel = 'fatal' | 'error' | 'warning' | 'info' | 'debug';
export const ALERT_LEVELS: readonly AlertLevel[] = [
  'fatal',
  'error',
  'warning',
  'info',
  'debug',
] as const;

export interface AlertRuleConditions {
  level?: { in: AlertLevel[] };
  firstSeenWindow?: string;
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

export interface AlertRuleWriteBody {
  name: string;
  conditions: AlertRuleConditions;
  actions: AlertRuleActions;
  throttleSeconds: number;
  enabled: boolean;
}

export async function alertsList(projectId: number, config: CliConfig): Promise<AlertRule[]> {
  return apiFetch<AlertRule[]>(config, `/api/v1/projects/${projectId}/alert-rules`);
}

export async function alertsGet(
  projectId: number,
  ruleId: number,
  config: CliConfig,
): Promise<AlertRule> {
  return apiFetch<AlertRule>(config, `/api/v1/projects/${projectId}/alert-rules/${ruleId}`);
}

export async function alertsCreate(
  projectId: number,
  body: AlertRuleWriteBody,
  config: CliConfig,
): Promise<AlertRule> {
  return apiFetch<AlertRule>(config, `/api/v1/projects/${projectId}/alert-rules`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function alertsUpdate(
  projectId: number,
  ruleId: number,
  body: AlertRuleWriteBody,
  config: CliConfig,
): Promise<AlertRule> {
  return apiFetch<AlertRule>(config, `/api/v1/projects/${projectId}/alert-rules/${ruleId}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export async function alertsDelete(
  projectId: number,
  ruleId: number,
  config: CliConfig,
): Promise<void> {
  return apiFetch<void>(config, `/api/v1/projects/${projectId}/alert-rules/${ruleId}`, {
    method: 'DELETE',
  });
}

// ── helpers shared by the CLI dispatcher ────────────────────────────────────

/**
 * Accepts shorthand like {@code 5m}, {@code 2h}, {@code 1d}, or a raw ISO-8601 duration
 * ({@code PT30S}, {@code P1DT12H}) — anything starting with {@code P} passes through unchanged so
 * power users keep the full DSL. Returns null when the input is meaningless so the dispatcher
 * surfaces a usage hint instead of POSTing a malformed body.
 */
export function parseWindowShorthand(raw: string): string | null {
  if (!raw) return null;
  if (raw.startsWith('P') || raw.startsWith('p')) return raw.toUpperCase();
  const m = raw.match(/^(\d+)\s*(m|h|d)$/i);
  if (!m) return null;
  const n = Number(m[1]);
  if (!Number.isFinite(n) || n <= 0) return null;
  const unit = (m[2] ?? '').toLowerCase();
  switch (unit) {
    case 'm':
      return `PT${n}M`;
    case 'h':
      return `PT${n}H`;
    case 'd':
      return `P${n}D`;
  }
  return null;
}

/**
 * Comma-split with the 5-level whitelist enforced. Returns null on any unknown value so the
 * dispatcher fails fast (the api would return 400 anyway, but we'd rather not round-trip).
 */
export function parseLevels(raw: string): AlertLevel[] | null {
  const parts = raw
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter((s) => s.length > 0);
  if (parts.length === 0) return null;
  for (const p of parts) {
    if (!ALERT_LEVELS.includes(p as AlertLevel)) return null;
  }
  return parts as AlertLevel[];
}

export function parseDestinationIds(raw: string[] | undefined): number[] | null {
  if (!raw || raw.length === 0) return null;
  const out: number[] = [];
  for (const item of raw) {
    for (const piece of item.split(',')) {
      const trimmed = piece.trim();
      if (!trimmed) continue;
      const n = Number(trimmed);
      if (!Number.isInteger(n) || n <= 0) return null;
      out.push(n);
    }
  }
  return out.length === 0 ? null : out;
}

export function parseTagValuesFlag(raw: string): string[] {
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

/**
 * Builds a body for create / update from already-parsed flag values. {@code base} carries the
 * current rule on update so unspecified flags inherit instead of being cleared.
 */
export function composeBody(args: {
  base?: AlertRule;
  name?: string;
  levels?: AlertLevel[];
  firstSeenWindow?: string;
  occurrenceThreshold?: number;
  tagKey?: string;
  tagValues?: string[];
  destinationIds?: number[];
  throttleSeconds?: number;
  enabled?: boolean;
}): AlertRuleWriteBody {
  const conditions: AlertRuleConditions = { ...(args.base?.conditions ?? {}) };
  if (args.levels !== undefined) conditions.level = { in: args.levels };
  if (args.firstSeenWindow !== undefined) conditions.firstSeenWindow = args.firstSeenWindow;
  if (args.occurrenceThreshold !== undefined) {
    conditions.occurrenceThreshold = args.occurrenceThreshold;
  }
  if (args.tagKey !== undefined && args.tagValues !== undefined && args.tagValues.length > 0) {
    conditions.tag = { key: args.tagKey, in: args.tagValues };
  }
  const actions: AlertRuleActions = args.destinationIds
    ? { destinationIds: args.destinationIds }
    : { destinationIds: args.base?.actions.destinationIds ?? [] };
  return {
    name: args.name ?? args.base?.name ?? '',
    conditions,
    actions,
    throttleSeconds: args.throttleSeconds ?? args.base?.throttleSeconds ?? 300,
    enabled: args.enabled ?? args.base?.enabled ?? true,
  };
}
