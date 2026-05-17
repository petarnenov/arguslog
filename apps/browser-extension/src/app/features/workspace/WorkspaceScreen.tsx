import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { getConnectionStatus } from '../../../shared/domain/connection';
import { getPageContext, listDsns, listMembers, listMyOrgs, listProjects, updateWorkspaceSelection } from '../../../shared/domain/workspace';
import { Badge, Button, Card, EmptyState, Page, Select } from '../../../shared/ui/components/primitives';

function buildRecents(selection: {
  orgSlug?: string | undefined;
  orgId?: number | undefined;
  projectId?: number | undefined;
  issueId?: number | undefined;
}) {
  const recents = [];
  if (selection.orgId) {
    recents.push({ type: 'org' as const, id: String(selection.orgId), label: selection.orgSlug ?? `Org ${selection.orgId}` });
  }
  if (selection.projectId) {
    recents.push({ type: 'project' as const, id: String(selection.projectId), label: `Project ${selection.projectId}` });
  }
  if (selection.issueId) {
    recents.push({ type: 'issue' as const, id: String(selection.issueId), label: `Issue #${selection.issueId}` });
  }
  return recents;
}

export function WorkspaceScreen() {
  const queryClient = useQueryClient();
  const statusQuery = useQuery({
    queryKey: ['connection-status'],
    queryFn: getConnectionStatus,
  });
  const orgsQuery = useQuery({
    queryKey: ['orgs'],
    queryFn: listMyOrgs,
    enabled: statusQuery.data?.authSession.patPresent ?? false,
  });

  const [orgId, setOrgId] = useState<number | undefined>();
  const [orgSlug, setOrgSlug] = useState<string | undefined>();
  const [projectId, setProjectId] = useState<number | undefined>();

  useEffect(() => {
    setOrgId(statusQuery.data?.workspaceSelection.orgId);
    setOrgSlug(statusQuery.data?.workspaceSelection.orgSlug);
    setProjectId(statusQuery.data?.workspaceSelection.projectId);
  }, [statusQuery.data?.workspaceSelection]);

  const projectsQuery = useQuery({
    queryKey: ['projects', orgId],
    queryFn: () => listProjects(orgId!),
    enabled: Boolean(orgId),
  });

  const membersQuery = useQuery({
    queryKey: ['members', orgId],
    queryFn: () => listMembers(orgId!),
    enabled: Boolean(orgId),
  });

  const dsnsQuery = useQuery({
    queryKey: ['dsns', projectId],
    queryFn: () => listDsns(projectId!),
    enabled: Boolean(projectId),
  });

  const pageContextQuery = useQuery({
    queryKey: ['page-context'],
    queryFn: getPageContext,
    enabled: statusQuery.data?.authSession.patPresent ?? false,
  });

  const updateSelectionMutation = useMutation({
    mutationFn: updateWorkspaceSelection,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['connection-status'] });
    },
  });

  const selectedOrg = orgsQuery.data?.find((org) => org.id === orgId);
  const selectedProject = projectsQuery.data?.find((project) => project.id === projectId);

  const capabilitySummary = useMemo(
    () =>
      statusQuery.data?.capabilitySnapshot
        ? `${statusQuery.data.capabilitySnapshot.toolNames.length} tools · ${statusQuery.data.capabilitySnapshot.promptIds.length} prompts`
        : 'Capability snapshot unavailable',
    [statusQuery.data?.capabilitySnapshot],
  );

  if (!statusQuery.data?.authSession.patPresent) {
    return (
      <Page title="Workspace" subtitle="Connect first to browse organizations, projects, DSNs, and members.">
        <EmptyState title="No PAT configured" description="Open the Connect or Settings tab and authenticate with an Arguslog personal access token." />
      </Page>
    );
  }

  return (
    <Page
      title="Workspace"
      subtitle={`Capability snapshot: ${capabilitySummary}`}
      actions={
        statusQuery.data.pageContext?.issueId ? (
          <Badge tone="success">Issue #{statusQuery.data.pageContext.issueId} detected on page</Badge>
        ) : undefined
      }
    >
      <Card title="Current selection">
        <div className="grid gap-3 md:grid-cols-2">
          <div className="space-y-2">
            <label htmlFor="workspace-org" className="text-xs uppercase tracking-wide text-slate-400">Organization</label>
            <Select
              id="workspace-org"
              value={orgId ?? ''}
              onChange={(event) => {
                const nextOrgId = Number(event.target.value);
                const org = orgsQuery.data?.find((candidate) => candidate.id === nextOrgId);
                setOrgId(nextOrgId);
                setOrgSlug(org?.slug);
                setProjectId(undefined);
                updateSelectionMutation.mutate({
                  orgId: nextOrgId,
                  orgSlug: org?.slug,
                  projectId: undefined,
                  issueId: statusQuery.data?.workspaceSelection.issueId,
                  recents: buildRecents({
                    orgId: nextOrgId,
                    orgSlug: org?.slug,
                    issueId: statusQuery.data?.workspaceSelection.issueId,
                  }),
                });
              }}
            >
              <option value="">Select an org</option>
              {orgsQuery.data?.map((org) => (
                <option key={org.id} value={org.id}>
                  {org.name}
                </option>
              ))}
            </Select>
          </div>

          <div className="space-y-2">
            <label htmlFor="workspace-project" className="text-xs uppercase tracking-wide text-slate-400">Project</label>
            <Select
              id="workspace-project"
              disabled={!orgId}
              value={projectId ?? ''}
              onChange={(event) => {
                const nextProjectId = Number(event.target.value);
                setProjectId(nextProjectId);
                updateSelectionMutation.mutate({
                  orgId,
                  orgSlug,
                  projectId: nextProjectId,
                  issueId: statusQuery.data?.workspaceSelection.issueId,
                  recents: buildRecents({
                    orgId,
                    orgSlug,
                    projectId: nextProjectId,
                    issueId: statusQuery.data?.workspaceSelection.issueId,
                  }),
                });
              }}
            >
              <option value="">Select a project</option>
              {projectsQuery.data?.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
            </Select>
          </div>
        </div>

        {pageContextQuery.data ? (
          <div className="mt-4 rounded-xl border border-blue-500/30 bg-blue-500/10 p-3 text-sm text-slate-200">
            <p className="font-medium text-white">Detected Argus page context</p>
            <p className="mt-1">
              orgSlug={pageContextQuery.data.orgSlug ?? 'n/a'} · projectId={pageContextQuery.data.projectId ?? 'n/a'} · issueId={pageContextQuery.data.issueId ?? 'n/a'}
            </p>
            <div className="mt-3">
              <Button
                variant="secondary"
                onClick={() => {
                  setOrgSlug(pageContextQuery.data?.orgSlug);
                  setProjectId(pageContextQuery.data?.projectId);
                  updateSelectionMutation.mutate({
                    orgId,
                    orgSlug: pageContextQuery.data?.orgSlug,
                    projectId: pageContextQuery.data?.projectId,
                    issueId: pageContextQuery.data?.issueId,
                    recents: buildRecents({
                      orgId,
                      orgSlug: pageContextQuery.data?.orgSlug,
                      projectId: pageContextQuery.data?.projectId,
                      issueId: pageContextQuery.data?.issueId,
                    }),
                  });
                }}
              >
                Apply page context
              </Button>
            </div>
          </div>
        ) : null}
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title={`Members${selectedOrg ? ` · ${selectedOrg.name}` : ''}`}>
          {membersQuery.data?.length ? (
            <div className="space-y-2">
              {membersQuery.data.map((member) => (
                <div key={`${member.userId ?? member.email}-${member.role}`} className="rounded-xl border border-slate-800 bg-slate-950/50 p-3 text-sm">
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <p className="font-medium text-white">{member.displayName ?? member.email ?? member.userId ?? 'Unknown member'}</p>
                      <p className="text-slate-400">{member.email ?? member.userId}</p>
                    </div>
                    <Badge>{member.role ?? 'member'}</Badge>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="No members loaded" description="Select an organization to fetch the current member list." />
          )}
        </Card>

        <Card title={`DSNs${selectedProject ? ` · ${selectedProject.name}` : ''}`}>
          {dsnsQuery.data?.length ? (
            <div className="space-y-2">
              {dsnsQuery.data.map((dsn) => (
                <div key={`${dsn.id ?? dsn.dsnPublic}`} className="rounded-xl border border-slate-800 bg-slate-950/50 p-3 text-sm">
                  <p className="font-medium text-white">{dsn.name ?? 'Default DSN'}</p>
                  <p className="mt-1 break-all text-slate-400">{dsn.dsnPublic ?? dsn.dsn ?? 'No DSN value returned'}</p>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="No DSNs loaded" description="Select a project to inspect active DSN keys." />
          )}
        </Card>
      </div>
    </Page>
  );
}
