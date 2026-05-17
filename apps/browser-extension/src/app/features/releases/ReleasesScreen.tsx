import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { getConnectionStatus } from '../../../shared/domain/connection';
import { createRelease, getRelease, listReleases } from '../../../shared/domain/releases';
import { ConfirmDialog } from '../../../shared/ui/components/ConfirmDialog';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Input,
  Page,
  Textarea,
} from '../../../shared/ui/components/primitives';

export function ReleasesScreen() {
  const queryClient = useQueryClient();
  const statusQuery = useQuery({ queryKey: ['connection-status'], queryFn: getConnectionStatus });
  const projectId = statusQuery.data?.workspaceSelection.projectId;

  const [selectedReleaseId, setSelectedReleaseId] = useState<number | undefined>();
  const [form, setForm] = useState({ version: '', environment: '', commit: '' });
  const [confirmOpen, setConfirmOpen] = useState(false);

  const releasesQuery = useQuery({
    queryKey: ['releases', projectId],
    queryFn: () => listReleases(projectId!),
    enabled: Boolean(projectId),
  });

  const releaseDetailQuery = useQuery({
    queryKey: ['release', projectId, selectedReleaseId],
    queryFn: () => getRelease(projectId!, selectedReleaseId!),
    enabled: Boolean(projectId && selectedReleaseId),
  });

  const createMutation = useMutation({
    mutationFn: () =>
      createRelease({
        projectId: projectId!,
        body: {
          version: form.version,
          environment: form.environment || undefined,
          commit: form.commit || undefined,
        },
      }),
    onSuccess: async (release) => {
      setSelectedReleaseId(release.id);
      setConfirmOpen(false);
      setForm({ version: '', environment: '', commit: '' });
      await queryClient.invalidateQueries({ queryKey: ['releases', projectId] });
    },
  });

  const detailJson = useMemo(
    () => JSON.stringify(releaseDetailQuery.data ?? {}, null, 2),
    [releaseDetailQuery.data],
  );

  if (!projectId) {
    return (
      <Page title="Releases" subtitle="Select a project in Workspace first.">
        <EmptyState
          title="No project selected"
          description="Release inspection and creation are project-scoped."
        />
      </Page>
    );
  }

  return (
    <Page
      title="Releases"
      subtitle={`List, inspect, and create releases for project ${projectId}.`}
    >
      <Card title="Create release">
        <div className="grid gap-3 md:grid-cols-3">
          <Input
            value={form.version}
            onChange={(event) =>
              setForm((current) => ({ ...current, version: event.target.value }))
            }
            placeholder="Version"
          />
          <Input
            value={form.environment}
            onChange={(event) =>
              setForm((current) => ({ ...current, environment: event.target.value }))
            }
            placeholder="Environment"
          />
          <Input
            value={form.commit}
            onChange={(event) => setForm((current) => ({ ...current, commit: event.target.value }))}
            placeholder="Commit SHA"
          />
        </div>
        <div className="mt-3 flex justify-end">
          <Button disabled={!form.version} onClick={() => setConfirmOpen(true)}>
            Create release
          </Button>
        </div>
      </Card>

      <div className="grid gap-4 lg:grid-cols-[1.05fr,0.95fr]">
        <Card title="Release list">
          <div className="space-y-2">
            {releasesQuery.data?.map((release) => (
              <button
                key={release.id}
                type="button"
                onClick={() => setSelectedReleaseId(release.id)}
                className={`w-full rounded-xl border p-3 text-left transition ${
                  selectedReleaseId === release.id
                    ? 'border-blue-400 bg-blue-500/10'
                    : 'border-slate-800 bg-slate-950/50 hover:border-slate-600'
                }`}
              >
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <p className="font-medium text-white">{release.version}</p>
                    <p className="text-sm text-slate-400">
                      {release.gitSha ?? release.gitRef ?? 'No git metadata'}
                    </p>
                  </div>
                  <Badge>{release.deployStage ?? 'release'}</Badge>
                </div>
              </button>
            ))}
            {!releasesQuery.data?.length ? (
              <EmptyState
                title="No releases found"
                description="Create the first release or wait for one to be registered."
              />
            ) : null}
          </div>
        </Card>

        <Card
          title={
            releaseDetailQuery.data
              ? `Release ${releaseDetailQuery.data.version}`
              : 'Release detail'
          }
        >
          {releaseDetailQuery.data ? (
            <div className="space-y-3">
              <div className="flex flex-wrap gap-2">
                <Badge>{releaseDetailQuery.data.deployStage ?? 'n/a'}</Badge>
                <Badge>{releaseDetailQuery.data.releasedAt ?? 'draft'}</Badge>
              </div>
              <Textarea readOnly rows={16} value={detailJson} />
            </div>
          ) : (
            <EmptyState
              title="No release selected"
              description="Pick a release to inspect the full server payload."
            />
          )}
        </Card>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="Confirm release creation"
        description={`Create release "${form.version}" for project ${projectId}?`}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => createMutation.mutate()}
      />
    </Page>
  );
}
