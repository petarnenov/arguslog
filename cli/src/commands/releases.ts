import type { CliConfig } from '../config.js';
import { apiFetch } from '../http.js';

export interface Release {
  id: number;
  projectId: number;
  version: string;
  createdAt: string;
  releasedAt: string | null;
  gitSha: string | null;
  gitRef: string | null;
  deployStage: string | null;
  changelog: string | null;
}

/**
 * Operator-supplied payload for create / update. {@code version} is required on create; on update
 * every field is treated as full-PUT — a null clears the column.
 */
export interface ReleasePayload {
  version: string;
  releasedAt?: string | null;
  gitSha?: string | null;
  gitRef?: string | null;
  deployStage?: string | null;
  changelog?: string | null;
}

export interface ReleasesNewArgs {
  version: string;
  projectId: number;
  releasedAt?: string;
  gitSha?: string;
  gitRef?: string;
  deployStage?: string;
  changelog?: string;
}

export async function releasesNew(args: ReleasesNewArgs, config: CliConfig): Promise<Release> {
  const body: ReleasePayload = {
    version: args.version,
    releasedAt: args.releasedAt ?? null,
    gitSha: args.gitSha ?? null,
    gitRef: args.gitRef ?? null,
    deployStage: args.deployStage ?? null,
    changelog: args.changelog ?? null,
  };
  return apiFetch<Release>(config, `/api/v1/projects/${args.projectId}/releases`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function releasesList(projectId: number, config: CliConfig): Promise<Release[]> {
  return apiFetch<Release[]>(config, `/api/v1/projects/${projectId}/releases`);
}

export async function releasesGet(
  projectId: number,
  releaseId: number,
  config: CliConfig,
): Promise<Release> {
  return apiFetch<Release>(config, `/api/v1/projects/${projectId}/releases/${releaseId}`);
}

export interface ReleasesUpdateArgs {
  projectId: number;
  releaseId: number;
  /**
   * Fields to overwrite. A field that's `undefined` keeps the existing value (fetched via GET
   * before the PUT); a field that's an empty string is treated as "clear" and serialized as null.
   * `version` is required on the wire so the caller must always send it.
   */
  version?: string;
  releasedAt?: string;
  gitSha?: string;
  gitRef?: string;
  deployStage?: string;
  changelog?: string;
}

/**
 * The release controller uses full-PUT semantics, so we fetch the current row first and merge the
 * operator-supplied overrides on top. Empty-string overrides clear the column; undefined leaves
 * the existing value untouched.
 */
export async function releasesUpdate(
  args: ReleasesUpdateArgs,
  config: CliConfig,
): Promise<Release> {
  const current = await releasesGet(args.projectId, args.releaseId, config);
  const body: ReleasePayload = {
    version: args.version ?? current.version,
    releasedAt: pickOrCarry(args.releasedAt, current.releasedAt),
    gitSha: pickOrCarry(args.gitSha, current.gitSha),
    gitRef: pickOrCarry(args.gitRef, current.gitRef),
    deployStage: pickOrCarry(args.deployStage, current.deployStage),
    changelog: pickOrCarry(args.changelog, current.changelog),
  };
  return apiFetch<Release>(
    config,
    `/api/v1/projects/${args.projectId}/releases/${args.releaseId}`,
    { method: 'PUT', body: JSON.stringify(body) },
  );
}

function pickOrCarry(override: string | undefined, existing: string | null): string | null {
  if (override === undefined) return existing;
  if (override === '') return null;
  return override;
}

export async function releasesDelete(
  projectId: number,
  releaseId: number,
  config: CliConfig,
): Promise<void> {
  await apiFetch<void>(config, `/api/v1/projects/${projectId}/releases/${releaseId}`, {
    method: 'DELETE',
  });
}
