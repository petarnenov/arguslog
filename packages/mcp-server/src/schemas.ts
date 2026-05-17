import { z } from 'zod';

const NullableString = z.string().nullable();
const NullableNumber = z.number().nullable();

export const OrgSummarySchema = z.object({
  id: z.number().int(),
  slug: z.string(),
  name: z.string(),
  plan: z.string().optional(),
  createdAt: z.string().optional(),
});

export const ProjectSummarySchema = z.object({
  id: z.number().int(),
  orgId: z.number().int().optional(),
  slug: z.string().optional(),
  name: z.string(),
  platform: z.string().nullable().optional(),
  createdAt: z.string().optional(),
  archivedAt: NullableString.optional(),
});

export const MeSchema = z.object({
  userId: z.string(),
  email: z.string().email(),
  displayName: z.string().nullable().optional(),
  isPlatformAdmin: z.boolean().optional(),
  tier: z.string().optional(),
  tierExpiresAt: NullableString.optional(),
  tierReason: NullableString.optional(),
});

export const MemberSchema = z.object({
  userId: z.string().optional(),
  email: z.string().email().optional(),
  displayName: z.string().nullable().optional(),
  role: z.string().optional(),
  joinedAt: z.string().optional(),
});

export const IssueStatusSchema = z.enum(['unresolved', 'resolved', 'ignored']);
export const IssueLevelSchema = z.enum(['fatal', 'error', 'warning', 'info', 'debug']);

export const IssueSummarySchema = z.object({
  id: z.number().int(),
  title: z.string().catch('Untitled issue'),
  culprit: z.string().nullable().optional(),
  level: z.string().optional(),
  status: z.string().optional(),
  count: z.number().int().optional(),
  firstSeenAt: z.string().optional(),
  lastSeenAt: z.string().optional(),
  assigneeUserId: z.string().nullable().optional(),
  firstSeenReleaseId: NullableNumber.optional(),
  projectId: z.number().int().optional(),
});

export const StackFrameSchema = z.object({
  filename: z.string().optional(),
  line: z.number().int().optional(),
  column: z.number().int().optional(),
  function: z.string().optional(),
});

export const IssueDetailSchema = IssueSummarySchema.extend({
  fingerprint: z.array(z.string()).optional(),
  currentReleaseId: NullableNumber.optional(),
  latestEvent: z
    .object({
      id: z.number().int().optional(),
      title: z.string().optional(),
      message: z.string().optional(),
      stacktrace: z
        .object({
          frames: z.array(StackFrameSchema).optional(),
        })
        .optional(),
    })
    .optional(),
});

export const IssueEventSchema = z.object({
  id: z.number().int().optional(),
  issueId: z.number().int().optional(),
  occurredAt: z.string().optional(),
  title: z.string().optional(),
  message: z.string().optional(),
  level: z.string().optional(),
  request: z.record(z.unknown()).optional(),
  contexts: z.record(z.unknown()).optional(),
  breadcrumbs: z.array(z.record(z.unknown())).optional(),
  exception: z.record(z.unknown()).optional(),
  stacktrace: z
    .object({
      frames: z.array(StackFrameSchema).optional(),
    })
    .optional(),
});

export const ReleaseSummarySchema = z.object({
  id: z.number().int(),
  projectId: z.number().int().optional(),
  version: z.string(),
  createdAt: z.string().optional(),
  releasedAt: z.string().optional(),
  gitSha: z.string().optional(),
  gitRef: z.string().optional(),
  deployStage: z.string().optional(),
  changelog: z.string().optional(),
});

export const DsnSchema = z.object({
  id: z.number().int().optional(),
  projectId: z.number().int().optional(),
  name: z.string().optional(),
  dsn: z.string().optional(),
  dsnPublic: z.string().optional(),
  createdAt: z.string().optional(),
});

export const CreateProjectResultSchema = z.object({
  project: ProjectSummarySchema,
  dsn: DsnSchema.optional(),
});

export const ListProjectsInputSchema = z.object({
  orgId: z.number().int().positive(),
});

export const ListIssuesInputSchema = z.object({
  projectId: z.number().int().positive(),
  status: IssueStatusSchema.optional(),
  level: IssueLevelSchema.optional(),
  q: z.string().optional(),
  assignee: z.string().optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(200).optional(),
  firstSeenReleaseId: z.union([z.number().int(), z.string()]).optional(),
  seenInReleaseId: z.union([z.number().int(), z.string()]).optional(),
});

export const GetIssueInputSchema = z.object({
  projectId: z.number().int().positive(),
  issueId: z.number().int().positive(),
});

export const ListIssueEventsInputSchema = GetIssueInputSchema.extend({
  afterId: z.number().int().positive().optional(),
  limit: z.number().int().min(1).max(200).optional(),
});

export const TriageIssueInputSchema = GetIssueInputSchema.extend({
  body: z.object({
    status: IssueStatusSchema,
  }),
});

export const AssignIssueInputSchema = GetIssueInputSchema.extend({
  body: z.object({
    userId: z.string().uuid().nullable(),
  }),
});

export const CreateProjectInputSchema = z.object({
  orgId: z.number().int().positive(),
  body: z.object({
    name: z.string().min(2).max(100),
    platform: z.string().min(1),
  }),
});

export const CreateReleaseInputSchema = z.object({
  projectId: z.number().int().positive(),
  body: z.object({
    version: z.string().min(1),
    environment: z.string().optional(),
    commit: z.string().optional(),
  }),
});

export const ListMembersInputSchema = z.object({
  orgId: z.number().int().positive(),
});

export const ListDsnsInputSchema = z.object({
  projectId: z.number().int().positive(),
});

export const GetReleaseInputSchema = z.object({
  projectId: z.number().int().positive(),
  id: z.number().int().positive(),
});

export const ListReleaseInputSchema = z.object({
  projectId: z.number().int().positive(),
});

export const CuratedToolInputSchemas = {
  list_projects: ListProjectsInputSchema,
  list_issues: ListIssuesInputSchema,
  triage_issue: TriageIssueInputSchema,
  assign_issue: AssignIssueInputSchema,
  get_issue: GetIssueInputSchema,
  list_issue_events: ListIssueEventsInputSchema,
  create_project: CreateProjectInputSchema,
  create_release: CreateReleaseInputSchema,
  list_members: ListMembersInputSchema,
  list_dsns: ListDsnsInputSchema,
  list_release: ListReleaseInputSchema,
  get_release: GetReleaseInputSchema,
} as const;

export const CuratedToolOutputSchemas = {
  list_my_orgs: z.array(OrgSummarySchema),
  list_projects: z.array(ProjectSummarySchema),
  list_issues: z.array(IssueSummarySchema),
  triage_issue: IssueDetailSchema,
  assign_issue: IssueDetailSchema,
  get_issue: IssueDetailSchema,
  list_issue_events: z.array(IssueEventSchema),
  create_project: CreateProjectResultSchema,
  create_release: ReleaseSummarySchema,
  list_members: z.array(MemberSchema),
  list_dsns: z.array(DsnSchema),
  get_me: MeSchema,
  list_release: z.array(ReleaseSummarySchema),
  get_release: ReleaseSummarySchema,
} as const;

export type OrgSummary = z.infer<typeof OrgSummarySchema>;
export type ProjectSummary = z.infer<typeof ProjectSummarySchema>;
export type Me = z.infer<typeof MeSchema>;
export type Member = z.infer<typeof MemberSchema>;
export type IssueSummary = z.infer<typeof IssueSummarySchema>;
export type IssueDetail = z.infer<typeof IssueDetailSchema>;
export type IssueEvent = z.infer<typeof IssueEventSchema>;
export type ReleaseSummary = z.infer<typeof ReleaseSummarySchema>;
export type Dsn = z.infer<typeof DsnSchema>;
