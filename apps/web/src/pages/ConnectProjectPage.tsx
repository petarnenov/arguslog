import { buildSyntheticEvent, parseDsn } from '@arguslog/sdk-react';
import {
  Alert,
  Badge,
  Button,
  Center,
  Code,
  CopyButton,
  Group,
  Loader,
  Stack,
  Tabs,
  Text,
  Title,
} from '@mantine/core';
import {
  IconBolt,
  IconCheck,
  IconCopy,
  IconKey,
  IconPlugConnected,
  IconRefresh,
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useParams } from 'react-router';

import { ApiError } from '../api/client';
import { createDsn, type Dsn, type DsnSummary } from '../api/keys';
import { queryKeys, useDsns, useMyOrgs, useMyTokens, useProjects } from '../api/queries';
import { createMyToken, type PersonalAccessToken } from '../api/tokens';
import { OnboardingFlow } from '../components/connect/OnboardingFlow';
import { env } from '../env';
import { buildSnippets, type ConnectSnippet, type SnippetGroup } from '../lib/connectSnippets';

function describeApiError(err: unknown): string {
  return err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err);
}

/**
 * Rebuild a DSN string from its public part. `createDsn` returns the full string at mint time
 * once; subsequent {@code listDsns} responses only carry the public key (the secret was never
 * stored anywhere recoverable). For existing keys we reconstruct the URL using the public host
 * the dashboard already knows so the wizard can pre-fill snippets without re-issuing a key.
 */
function reconstructDsn(dsnPublic: string, projectId: number, ingestBaseUrl: string): string {
  const host = new URL(ingestBaseUrl).host;
  return `arguslog://${dsnPublic}@${host}/api/${projectId}`;
}

/**
 * PAT name prefix for tokens minted by the Connect wizard's auto-provision flow. The exact
 * name is `Connect quickstart — <project name>` for the very first visit, and
 * `Connect quickstart — <project name> — <YYYY-MM-DD>` for subsequent rotations. We match by
 * prefix to detect "user already passed through Connect for this project at least once" so
 * the auto-provision useEffect doesn't fire on every revisit.
 */
const PAT_QUICKSTART_PREFIX = 'Connect quickstart';
const PAT_DEFAULT_SCOPES = [
  'orgs:read',
  'projects:read',
  'issues:read',
  'events:read',
  'releases:read',
  'releases:write',
  'sourcemaps:write',
] as const;

export function ConnectProjectPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { orgSlug, projectId: projectIdParam } = useParams();
  const projectId = Number(projectIdParam);

  const orgsQuery = useMyOrgs();
  const org = orgsQuery.data?.find((o) => o.slug === orgSlug);
  const projectsQuery = useProjects(org?.id);
  const project = projectsQuery.data?.find((p) => p.id === projectId);
  const dsnsQuery = useDsns(projectId, { enabled: Number.isFinite(projectId) });

  // Locally minted credentials live in state because their plaintext is gone after navigation
  // — we don't want React Query to "refetch" them away. Once the user has copied the snippet,
  // they're free to leave the page.
  const [freshDsn, setFreshDsn] = useState<Dsn | null>(null);
  const [freshPat, setFreshPat] = useState<PersonalAccessToken | null>(null);
  const [dsnError, setDsnError] = useState<string | null>(null);
  const [patError, setPatError] = useState<string | null>(null);

  const generateDsnMutation = useMutation({
    mutationFn: () => createDsn(projectId),
    onSuccess: async (dsn) => {
      setDsnError(null);
      setFreshDsn(dsn);
      await queryClient.invalidateQueries({ queryKey: queryKeys.dsns(projectId) });
    },
    onError: (err) => setDsnError(describeApiError(err)),
  });

  const [pingResult, setPingResult] = useState<{ ok: boolean; detail: string } | null>(null);

  const pingMutation = useMutation({
    mutationFn: async () => {
      if (!dsnString) throw new Error('no DSN available');
      // Reuse the SDK's wire-format builder so the synthetic event matches exactly what a real
      // SDK would POST — same fingerprintable shape, same headers, same path. If this fails we
      // know the real production flow would also fail; if it succeeds the user has end-to-end
      // confirmation that browser → ingest works for this project.
      const parsed = parseDsn(dsnString);
      const payload = buildSyntheticEvent({ source: 'connect-wizard' });
      const resp = await fetch(parsed.ingestUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Arguslog-Auth': `Arguslog DSN ${parsed.publicKey}`,
        },
        body: JSON.stringify(payload),
      });
      if (!resp.ok) {
        const body = await resp.text().catch(() => '');
        throw new Error(`HTTP ${resp.status}${body ? ` — ${body.slice(0, 200)}` : ''}`);
      }
      return payload;
    },
    onSuccess: (payload) => {
      setPingResult({
        ok: true,
        detail: `Event ${payload.eventId.slice(0, 8)}… accepted. Check the Issues page in ~1s.`,
      });
    },
    onError: (e: unknown) => {
      setPingResult({ ok: false, detail: e instanceof Error ? e.message : String(e) });
    },
  });

  const tokensQuery = useMyTokens();

  // Has THIS user passed through the Connect wizard at any point in the past? Plaintext is
  // gone after page navigation, so on a return visit we can only see the name — not the
  // token — and have to offer Rotate to refill the magic prompt.
  const hasExistingQuickstartPat = useMemo(
    () => (tokensQuery.data ?? []).some((t) => t.name.startsWith(PAT_QUICKSTART_PREFIX)),
    [tokensQuery.data],
  );

  const generatePatMutation = useMutation({
    mutationFn: (rotate: boolean = false) => {
      const projectLabel = project?.name ?? `project ${projectId}`;
      // First visit → `Connect quickstart — <project>`. Rotation → suffix with ISO date so
      // existing tokens stay valid until the user revokes them from /me/tokens.
      const name = rotate
        ? `${PAT_QUICKSTART_PREFIX} — ${projectLabel} — ${new Date().toISOString().slice(0, 10)}`
        : `${PAT_QUICKSTART_PREFIX} — ${projectLabel}`;
      return createMyToken({ name, scopes: [...PAT_DEFAULT_SCOPES] });
    },
    onSuccess: async (pat) => {
      setPatError(null);
      setFreshPat(pat);
      await queryClient.invalidateQueries({ queryKey: queryKeys.myTokens() });
    },
    onError: (err) => setPatError(describeApiError(err)),
  });

  // ─── Auto-provision: every page mount guarantees real DSN + PAT in the prompt ─────
  // The magic-prompt invariant is "no placeholders, ever" (see feedback memory
  // magic-prompt-inline-secrets). PAT plaintext is gone after navigation, so on every
  // fresh mount where freshPat is null we mint a new one — even if a prior "Connect
  // quickstart" PAT exists in /me/tokens. The user can revoke stale tokens at
  // app.arguslog.org/me/tokens or via the delete_me_tokens MCP tool; we choose token
  // churn over a broken paste-and-go UX. DSN is treated differently: existing active
  // DSNs are reconstructed into the prompt (the SDK only needs the public part), so we
  // only mint a new DSN when none exists. The ref guard keeps this strictly one-shot
  // per mount under React StrictMode.
  const autoProvisionStartedRef = useRef(false);
  useEffect(() => {
    if (autoProvisionStartedRef.current) return;
    if (!dsnsQuery.data || !tokensQuery.data) return;
    if (orgsQuery.isLoading || projectsQuery.isLoading) return;
    if (!org || !project) return;
    const hasAnyActiveDsn = dsnsQuery.data.some((d) => d.active);
    const needDsn = !freshDsn && !hasAnyActiveDsn;
    const needPat = !freshPat;
    if (!needDsn && !needPat) return;
    autoProvisionStartedRef.current = true;
    if (needDsn) generateDsnMutation.mutate();
    if (needPat) generatePatMutation.mutate(hasExistingQuickstartPat);
  }, [
    dsnsQuery.data,
    tokensQuery.data,
    orgsQuery.isLoading,
    projectsQuery.isLoading,
    org,
    project,
    freshDsn,
    freshPat,
    hasExistingQuickstartPat,
    generateDsnMutation,
    generatePatMutation,
  ]);

  const isAutoProvisioning =
    autoProvisionStartedRef.current &&
    (generateDsnMutation.isPending || generatePatMutation.isPending);

  // Rotate CTA stays visible as a power-user escape: even after auto-mint, the user may
  // want yet another fresh PAT (e.g., the just-issued one was screenshot-leaked). Click →
  // new PAT with a date-suffixed name; old ones stay valid until manually revoked from
  // /me/tokens or via the delete_me_tokens MCP tool.
  const showRotateCta = !isAutoProvisioning && hasExistingQuickstartPat;

  /**
   * Resolution order for the DSN string we paste into snippets:
   *   1. Freshly minted DSN (this session)         — full plaintext available
   *   2. First active key from the listing         — reconstructed from public + ingest host
   *   3. Null                                      — placeholder rendered in snippet
   */
  const dsnString: string | null = useMemo(() => {
    if (freshDsn) return freshDsn.dsn;
    const list: DsnSummary[] | undefined = dsnsQuery.data;
    const firstActive = list?.find((d) => d.active);
    if (firstActive) {
      return reconstructDsn(firstActive.dsnPublic, projectId, env.VITE_INGEST_BASE_URL);
    }
    return null;
  }, [freshDsn, dsnsQuery.data, projectId]);

  const patString = freshPat?.token ?? null;

  const snippets = useMemo(
    () =>
      buildSnippets({
        dsn: dsnString,
        pat: patString,
        // Production runs at arguslog.org; self-hosters override via VITE_API_BASE_URL and the
        // snippet builder emits ARGUSLOG_API_URL automatically when the value differs.
        apiUrl: deriveApiUrl(env.VITE_API_BASE_URL),
      }),
    [dsnString, patString],
  );

  const grouped = useMemo(() => groupSnippets(snippets), [snippets]);

  // ─── Loading / error guards ─────────────────────────────────────────────────
  if (!Number.isFinite(projectId)) {
    return <Navigate to="/orgs" replace />;
  }
  if (orgsQuery.isLoading || projectsQuery.isLoading) {
    return (
      <Center mih={200}>
        <Loader />
      </Center>
    );
  }
  if (!org || !project) {
    return (
      <Stack>
        <Title order={3}>{t('connect.title')}</Title>
        <Text c="dimmed">{t('connect.notFound')}</Text>
      </Stack>
    );
  }

  // ─── Render ─────────────────────────────────────────────────────────────────
  return (
    <Stack maw={960}>
      <Stack gap={4}>
        <Group gap="sm" align="center">
          <IconPlugConnected size={22} />
          <Title order={3}>{t('connect.title')}</Title>
        </Group>
        <Text c="dimmed" size="sm" maw={680}>
          {t('connect.subtitle', { name: project.name })}
        </Text>
      </Stack>

      {/* DSN section */}
      <Stack gap="xs">
        <Group gap="sm" align="center">
          <Title order={5}>{t('connect.dsn.title')}</Title>
          <Badge color="blue" variant="light">
            {t('connect.dsn.badge')}
          </Badge>
        </Group>
        <Text size="sm" c="dimmed">
          {t('connect.dsn.hint')}
        </Text>

        {dsnString ? (
          <Stack gap="xs">
            <Group gap="sm" wrap="nowrap" align="center">
              <Code block style={{ flex: 1, fontSize: 13 }} data-testid="connect-dsn-value">
                {dsnString}
              </Code>
              <CopyButton value={dsnString}>
                {({ copied, copy }) => (
                  <Button
                    variant={copied ? 'filled' : 'light'}
                    color={copied ? 'teal' : 'blue'}
                    onClick={copy}
                    leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                  >
                    {copied ? t('connect.copied') : t('connect.copy')}
                  </Button>
                )}
              </CopyButton>
              <Button
                variant="light"
                color="orange"
                loading={pingMutation.isPending}
                onClick={() => pingMutation.mutate()}
                leftSection={<IconBolt size={14} />}
                data-testid="connect-test-ping"
              >
                {t('connect.dsn.testPing')}
              </Button>
            </Group>
            {pingResult ? (
              <Alert
                color={pingResult.ok ? 'teal' : 'red'}
                variant="light"
                withCloseButton
                onClose={() => setPingResult(null)}
                data-testid="connect-test-ping-result"
              >
                {pingResult.detail}
              </Alert>
            ) : null}
          </Stack>
        ) : (
          <Group gap="sm">
            <Text size="sm">{t('connect.dsn.empty')}</Text>
            <Button
              variant="light"
              loading={generateDsnMutation.isPending}
              onClick={() => generateDsnMutation.mutate()}
              data-testid="connect-dsn-generate"
            >
              {t('connect.dsn.generate')}
            </Button>
          </Group>
        )}
        {dsnError ? (
          <Alert color="red" variant="light">
            {dsnError}
          </Alert>
        ) : null}
      </Stack>

      {/* PAT section */}
      <Stack gap="xs">
        <Group gap="sm" align="center">
          <Title order={5}>{t('connect.pat.title')}</Title>
          <Badge color="violet" variant="light">
            {t('connect.pat.badge')}
          </Badge>
        </Group>
        <Text size="sm" c="dimmed">
          {t('connect.pat.hint')}
        </Text>

        {freshPat?.token ? (
          <Stack gap="xs">
            <Alert color="yellow" variant="light" icon={<IconKey size={16} />}>
              {t('connect.pat.oneTimeWarning')}
            </Alert>
            <Group gap="sm" wrap="nowrap" align="center">
              <Code block style={{ flex: 1, fontSize: 13 }} data-testid="connect-pat-value">
                {freshPat.token}
              </Code>
              <CopyButton value={freshPat.token}>
                {({ copied, copy }) => (
                  <Button
                    variant={copied ? 'filled' : 'light'}
                    color={copied ? 'teal' : 'violet'}
                    onClick={copy}
                    leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                  >
                    {copied ? t('connect.copied') : t('connect.copy')}
                  </Button>
                )}
              </CopyButton>
            </Group>
          </Stack>
        ) : (
          <Group gap="sm">
            <Button
              variant="light"
              color="violet"
              loading={generatePatMutation.isPending}
              onClick={() => generatePatMutation.mutate(false)}
              data-testid="connect-pat-generate"
            >
              {t('connect.pat.generate')}
            </Button>
            <Button component={Link} to="/me/tokens" variant="subtle">
              {t('connect.pat.manage')}
            </Button>
          </Group>
        )}
        {patError ? (
          <Alert color="red" variant="light">
            {patError}
          </Alert>
        ) : null}
      </Stack>

      {/* Snippet tabs */}
      <Stack gap="xs">
        <Title order={5}>{t('connect.snippets.title')}</Title>
        <Text size="sm" c="dimmed">
          {t('connect.snippets.hint')}
        </Text>

        <Tabs defaultValue="agent" keepMounted={false}>
          <Tabs.List>
            <Tabs.Tab value="agent">{t('connect.snippets.group.agent')}</Tabs.Tab>
            <Tabs.Tab value="workflow">{t('connect.snippets.group.workflow')}</Tabs.Tab>
            <Tabs.Tab value="sdk">{t('connect.snippets.group.sdk')}</Tabs.Tab>
            <Tabs.Tab value="mcp">{t('connect.snippets.group.mcp')}</Tabs.Tab>
            <Tabs.Tab value="cli">{t('connect.snippets.group.cli')}</Tabs.Tab>
          </Tabs.List>

          <Tabs.Panel value="agent" pt="md">
            <Stack gap="xs">
              <Text size="sm" c="dimmed">
                {t('connect.snippets.agent.hint')}
              </Text>
              {isAutoProvisioning && (
                <Group gap="xs" align="center">
                  <Loader size="xs" />
                  <Text size="sm" c="dimmed">
                    {t('connect.snippets.agent.provisioning')}
                  </Text>
                </Group>
              )}
              {showRotateCta && (
                <Alert
                  color="blue"
                  variant="light"
                  title={t('connect.snippets.agent.rotateTitle')}
                  data-testid="connect-rotate-cta"
                >
                  <Stack gap="xs">
                    <Text size="sm">{t('connect.snippets.agent.rotateBody')}</Text>
                    <Group gap="xs">
                      <Button
                        size="xs"
                        leftSection={<IconRefresh size={14} />}
                        loading={generatePatMutation.isPending}
                        onClick={() => generatePatMutation.mutate(true)}
                        data-testid="connect-rotate-pat"
                      >
                        {t('connect.snippets.agent.rotate')}
                      </Button>
                    </Group>
                  </Stack>
                </Alert>
              )}
              {!isAutoProvisioning && !showRotateCta && (!dsnString || !patString) && (
                <Alert color="yellow" variant="light">
                  {t('connect.snippets.agent.missingCredentials')}
                </Alert>
              )}
              <SnippetSubTabs items={grouped.agent} />
            </Stack>
          </Tabs.Panel>
          <Tabs.Panel value="workflow" pt="md">
            <Stack gap="xs">
              <Text size="sm" c="dimmed">
                {t('connect.snippets.workflow.hint')}
              </Text>
              <SnippetSubTabs items={grouped.workflow} />
            </Stack>
          </Tabs.Panel>
          <Tabs.Panel value="sdk" pt="md">
            <SnippetSubTabs
              items={grouped.sdk}
              renderItem={(snippet) => {
                // SDK slugs that ship the workflow-first onboarding flow (env-driven
                // installer + verification checklist + recommended telemetry service).
                // Each one is opted in via its SDK_CATALOG entry carrying `initFiles[]`
                // + `extras`; the `<OnboardingFlow>` component renders the right N
                // steps purely from the catalog content — no per-SDK branches here.
                const workflowFirstSlugs = ['sdk-vue', 'sdk-react', 'sdk-nextjs', 'sdk-angular'];
                if (!workflowFirstSlugs.includes(snippet.id)) return null;
                const slug = snippet.id.replace(/^sdk-/, '');
                return (
                  <OnboardingFlow
                    slug={slug}
                    dsn={dsnString}
                    pingState={{
                      onPing: () => pingMutation.mutate(),
                      isPending: pingMutation.isPending,
                      result: pingResult,
                    }}
                  />
                );
              }}
            />
          </Tabs.Panel>
          <Tabs.Panel value="mcp" pt="md">
            <SnippetSubTabs items={grouped.mcp} />
          </Tabs.Panel>
          <Tabs.Panel value="cli" pt="md">
            <SnippetSubTabs items={grouped.cli} />
          </Tabs.Panel>
        </Tabs>
      </Stack>
    </Stack>
  );
}

// ───────────────────────────────────────────────────────────────────────────────

interface SnippetSubTabsProps {
  items: ConnectSnippet[];
  /**
   * Optional override that renders custom content for a specific snippet id. Returns
   * `null` to fall back to the default code-block layout. Used by the SDK tab to
   * special-case the Vue workflow-first onboarding flow without forking the whole
   * component — every other SDK keeps the simple copy-paste shape.
   */
  renderItem?: (snippet: ConnectSnippet) => React.ReactNode | null;
}

function SnippetSubTabs({ items, renderItem }: SnippetSubTabsProps) {
  const { t } = useTranslation();
  const [active, setActive] = useState<string | null>(items[0]?.id ?? null);
  const current = items.find((s) => s.id === active) ?? items[0];

  if (!current) return null;

  const custom = renderItem?.(current) ?? null;

  return (
    <Stack gap="sm">
      <Tabs value={active} onChange={setActive} variant="pills">
        <Tabs.List>
          {items.map((s) => (
            <Tabs.Tab key={s.id} value={s.id}>
              {s.client}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>

      {custom ?? (
        <>
          <Group gap="xs" align="center">
            <Badge size="sm" variant="outline">
              {current.language}
            </Badge>
            <Text size="xs" c="dimmed" style={{ flex: 1 }}>
              {current.description}
            </Text>
            <CopyButton value={current.code}>
              {({ copied, copy }) => (
                <Button
                  size="xs"
                  variant={copied ? 'filled' : 'light'}
                  color={copied ? 'teal' : 'gray'}
                  onClick={copy}
                  leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                  data-testid={`connect-snippet-copy-${current.id}`}
                >
                  {copied ? t('connect.copied') : t('connect.copy')}
                </Button>
              )}
            </CopyButton>
          </Group>

          <Code block style={{ whiteSpace: 'pre', overflowX: 'auto', fontSize: 13 }}>
            {current.code}
          </Code>
        </>
      )}
    </Stack>
  );
}

// ───────────────────────────────────────────────────────────────────────────────

function groupSnippets(all: ConnectSnippet[]): Record<SnippetGroup, ConnectSnippet[]> {
  return {
    agent: all.filter((s) => s.group === 'agent'),
    workflow: all.filter((s) => s.group === 'workflow'),
    sdk: all.filter((s) => s.group === 'sdk'),
    mcp: all.filter((s) => s.group === 'mcp'),
    cli: all.filter((s) => s.group === 'cli'),
  };
}

/**
 * The API base URL the user's tooling should hit. Production is the canonical arguslog.org
 * host (snippets stay clean); self-hosted deployments differ and the snippet builder picks
 * that up to emit ARGUSLOG_API_URL automatically.
 */
function deriveApiUrl(viteApiBaseUrl: string): string {
  // Heuristic: if the dashboard is running against the canonical prod api, we don't need to
  // splash ARGUSLOG_API_URL into every snippet. Self-host overrides surface as-is.
  if (viteApiBaseUrl.includes('arguslog.org')) return 'https://arguslog.org';
  return viteApiBaseUrl;
}
