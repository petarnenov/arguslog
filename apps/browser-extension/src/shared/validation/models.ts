import { z } from 'zod';

import { AppErrorSchema } from '../types/errors';

export const PersistenceModeSchema = z.enum(['persistent', 'session']);
export const ThemeSchema = z.enum(['system', 'dark', 'light']);

export const ExtensionSettingsSchema = z.object({
  endpoint: z.string().url(),
  persistenceMode: PersistenceModeSchema,
  debug: z.boolean(),
  theme: ThemeSchema,
});

export const AccountSummarySchema = z.object({
  userId: z.string().optional(),
  email: z.string().email().optional(),
  displayName: z.string().nullable().optional(),
  isPlatformAdmin: z.boolean().optional(),
  tier: z.string().optional(),
});

/**
 * Connection-health snapshot persisted alongside the auth session under
 * `AUTH_SESSION_KEY` (pat-vault.ts). Two fields, both optional:
 *
 * - `lastConnectedAt` — ISO timestamp of the most recent successful MCP call. Written
 *   by `recordConnectionSuccess()` from the transport's `withClient()` happy path.
 * - `lastAuthError` — the most recent auth-class error mapped by `mapTransportError()`
 *   (INVALID_PAT / INSUFFICIENT_SCOPE / THROTTLED / SERVER_UNAVAILABLE). Cleared on the
 *   next successful call so the ConnectionHealthBadge doesn't keep red after recovery.
 *
 * Non-auth errors (404 from a missing issue, etc.) deliberately don't touch these — they
 * belong in the diagnostic log, not the connection-health view.
 */
export const ConnectionErrorSnapshotSchema = z.object({
  code: z.string(),
  httpStatus: z.number().int().optional(),
  message: z.string(),
  occurredAt: z.string(),
});

export const AuthSessionSchema = z.object({
  patPresent: z.boolean(),
  persistenceMode: PersistenceModeSchema,
  accountSummary: AccountSummarySchema.optional(),
  lastConnectedAt: z.string().optional(),
  lastAuthError: ConnectionErrorSnapshotSchema.nullable().optional(),
});

export const CapabilitySnapshotSchema = z.object({
  serverVersion: z.string(),
  toolNames: z.array(z.string()),
  promptIds: z.array(z.string()),
  detectedScopes: z.array(z.string()),
  fetchedAt: z.string(),
});

export const RecentSelectionSchema = z.object({
  type: z.enum(['org', 'project', 'issue']),
  id: z.string(),
  label: z.string(),
});

export const WorkspaceSelectionSchema = z.object({
  orgId: z.number().int().optional(),
  orgSlug: z.string().optional(),
  projectId: z.number().int().optional(),
  issueId: z.number().int().optional(),
  recents: z.array(RecentSelectionSchema),
});

export const PageContextSchema = z.object({
  orgSlug: z.string().optional(),
  projectId: z.number().int().optional(),
  issueId: z.number().int().optional(),
  /**
   * Captured from the release-detail route `/orgs/{slug}/projects/{id}/releases/{version}`.
   * Operator-typed identifier — usually `v1.2.3` / `2026.05.17` / a git short SHA. Stored
   * as a string verbatim; never coerced to Number.
   */
  releaseVersion: z.string().optional(),
  sourceTabUrl: z.string().optional(),
  capturedAt: z.string(),
});

export const DiagnosticLogEntrySchema = z.object({
  ts: z.string(),
  op: z.string(),
  durationMs: z.number().nonnegative(),
  outcome: z.enum(['ok', 'error']),
  errorBucket: z.string().optional(),
  meta: z.record(z.unknown()).optional(),
});

export const DiagnosticBundleSchema = z.object({
  exportedAt: z.string(),
  settings: ExtensionSettingsSchema,
  authSession: AuthSessionSchema,
  capabilitySnapshot: CapabilitySnapshotSchema.optional(),
  logs: z.array(DiagnosticLogEntrySchema),
});

export const ConnectionStatusSchema = z.object({
  settings: ExtensionSettingsSchema,
  authSession: AuthSessionSchema,
  capabilitySnapshot: CapabilitySnapshotSchema.optional(),
  pageContext: PageContextSchema.optional(),
  workspaceSelection: WorkspaceSelectionSchema,
});

export const BackgroundEnvelopeSchema = z.union([
  z.object({
    ok: z.literal(true),
    data: z.unknown(),
  }),
  z.object({
    ok: z.literal(false),
    error: AppErrorSchema,
  }),
]);

export type ExtensionSettings = z.infer<typeof ExtensionSettingsSchema>;
export type AccountSummary = z.infer<typeof AccountSummarySchema>;
export type AuthSession = z.infer<typeof AuthSessionSchema>;
export type ConnectionErrorSnapshot = z.infer<typeof ConnectionErrorSnapshotSchema>;
export type CapabilitySnapshot = z.infer<typeof CapabilitySnapshotSchema>;
export type WorkspaceSelection = z.infer<typeof WorkspaceSelectionSchema>;
export type PageContext = z.infer<typeof PageContextSchema>;
export type DiagnosticLogEntry = z.infer<typeof DiagnosticLogEntrySchema>;
export type DiagnosticBundle = z.infer<typeof DiagnosticBundleSchema>;
export type ConnectionStatus = z.infer<typeof ConnectionStatusSchema>;
