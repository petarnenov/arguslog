/**
 * The last missing guarded-write affordance on the extension's side panel: create a new
 * project from within the workspace screen. Mirrors the pattern of ReleasesScreen's
 * "Create release" form — local form state + useMutation + ConfirmDialog gate.
 *
 * Gating per Phase 2 AC4: the card renders ONLY when the connected server advertises
 * `create_project` AND an org is selected. There's no "disabled with tooltip" middle
 * state — if the feature isn't available, the affordance is invisible. Restricted PATs
 * see a clean workspace screen, not a teasing-but-broken button.
 *
 * On success: invalidates the projects + workspace queries so the picker re-fetches,
 * then sets the new project as the active workspace selection.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { getConnectionStatus } from '../../../shared/domain/connection';
import { createProject } from '../../../shared/domain/projects';
import { updateWorkspaceSelection } from '../../../shared/domain/workspace';
import { useFeatureAvailability } from '../../../shared/hooks/useFeatureAvailability';
import { ConfirmDialog } from '../../../shared/ui/components/ConfirmDialog';
import { Button, Card, Input } from '../../../shared/ui/components/primitives';

interface CreateProjectCardProps {
  orgId: number | undefined;
  orgSlug?: string | undefined;
}

export function CreateProjectCard({ orgId, orgSlug }: CreateProjectCardProps) {
  const queryClient = useQueryClient();
  const projects = useFeatureAvailability('projects');

  const [name, setName] = useState('');
  const [platform, setPlatform] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);

  const mutation = useMutation({
    mutationFn: () => {
      if (orgId === undefined) {
        throw new Error('No organization selected. Pick one in the workspace selector first.');
      }
      return createProject({
        orgId,
        body: { name: name.trim(), platform: platform.trim() },
      });
    },
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      await queryClient.invalidateQueries({ queryKey: ['connection-status'] });
      const status = await getConnectionStatus();
      // Auto-select the freshly-created project so the operator can immediately use the
      // other workspace cards (issues, releases, DSNs) against it.
      await updateWorkspaceSelection({
        orgId: status.workspaceSelection.orgId,
        orgSlug: status.workspaceSelection.orgSlug,
        projectId: result.project.id,
        issueId: undefined,
        recents: status.workspaceSelection.recents,
      });
      await queryClient.invalidateQueries({ queryKey: ['connection-status'] });
      setName('');
      setPlatform('');
      setConfirmOpen(false);
    },
  });

  // AC4: hide the card entirely when the feature isn't available or no org is picked.
  if (!projects.available || orgId === undefined) {
    return null;
  }

  const submittable = name.trim().length >= 2 && platform.trim().length >= 1;

  return (
    <Card title="Create project">
      <div className="space-y-3">
        <Input
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="Project name (2-100 chars)"
        />
        <Input
          value={platform}
          onChange={(event) => setPlatform(event.target.value)}
          placeholder="Platform (e.g. javascript, python, java-spring)"
        />
        <div className="flex justify-end">
          <Button
            disabled={!submittable || mutation.isPending}
            onClick={() => setConfirmOpen(true)}
          >
            {mutation.isPending ? 'Creating…' : 'Create project'}
          </Button>
        </div>
        {mutation.isError ? (
          <p className="text-sm text-rose-300">
            Create failed: {(mutation.error as Error).message}
          </p>
        ) : null}
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="Create project"
        description={`Create project "${name.trim()}" (${platform.trim()}) under ${
          orgSlug ?? `org ${orgId}`
        }?`}
        confirmLabel="Create"
        onConfirm={() => mutation.mutate()}
        onCancel={() => setConfirmOpen(false)}
      />
    </Card>
  );
}
