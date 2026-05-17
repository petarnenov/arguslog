/**
 * Workflow-first onboarding panel for the Vue SDK (Phase B of arguslog-sdks#2).
 *
 * Renders the SDK_CATALOG['vue'] entry as 7 numbered steps instead of a single inline
 * snippet. The shape matches the issue author's recommendation: install → env vars →
 * installer module → mount → instrument one real workflow → verify a real event lands
 * → optional error boundary. Step 6 ("verify event received") accepts a callback +
 * status so it ties into the existing test-ping flow on ConnectProjectPage.
 *
 * The component is intentionally not a generic stepper — Vue's onboarding shape is
 * distinct enough that other SDKs reuse the simpler SnippetSubTabs layout. If
 * Angular/Next/etc. want a similar treatment later, lift the per-step card pattern
 * into a shared component then.
 */
import {
  Alert,
  Badge,
  Button,
  Code,
  CopyButton,
  Grid,
  Group,
  Paper,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { IconCheck, IconCopy, IconBolt } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

import { SDK_CATALOG } from '../../lib/connectSnippets';

import { PostInstallChecklist, type ChecklistItem } from './PostInstallChecklist';

interface Props {
  /** Real DSN to inline into the `.env.local` snippet. Falsy → keep `<DSN>` placeholder. */
  dsn: string | null;
  /** Existing test-event ping plumbing — wired to step 6's button + status badge. */
  pingState: {
    onPing: () => void;
    isPending: boolean;
    /** null = not yet attempted; ok=true success; ok=false failure */
    result: { ok: boolean; detail: string } | null;
  };
}

function vueEntry() {
  // Defensive: the catalog is statically typed, but we guard the access pattern so any
  // future drift (someone deletes the entry, renames the slug) fails loudly here rather
  // than at first render with an opaque undefined-access.
  const entry = SDK_CATALOG.find((p) => p.slug === 'vue');
  if (!entry || !('initFiles' in entry) || !entry.initFiles) {
    throw new Error('SDK_CATALOG is missing the `vue` entry with `initFiles[]`.');
  }
  return entry;
}

function inlineDsn(contents: string, dsn: string | null): string {
  if (!dsn) return contents;
  return contents.replaceAll('<DSN>', dsn);
}

function FileCard({ path, contents, lang }: { path: string; contents: string; lang?: string }) {
  return (
    <Stack gap={6}>
      <Group justify="space-between" align="center">
        <Code fw={600}>{path}</Code>
        <CopyButton value={contents}>
          {({ copied, copy }) => (
            <Button
              size="xs"
              variant={copied ? 'filled' : 'light'}
              color={copied ? 'teal' : 'gray'}
              onClick={copy}
              leftSection={copied ? <IconCheck size={12} /> : <IconCopy size={12} />}
              data-testid={`vue-onboarding-copy-${path}`}
            >
              {copied ? 'Copied' : 'Copy'}
            </Button>
          )}
        </CopyButton>
      </Group>
      <Code block style={{ whiteSpace: 'pre', overflowX: 'auto', fontSize: 13 }}>
        {contents}
      </Code>
      {lang ? (
        <Badge size="xs" variant="outline" w="fit-content">
          {lang}
        </Badge>
      ) : null}
    </Stack>
  );
}

function Step({
  index,
  title,
  description,
  children,
  testid,
}: {
  index: number;
  title: string;
  description?: string;
  children: React.ReactNode;
  testid?: string;
}) {
  return (
    <Paper p="md" withBorder radius="md" data-testid={testid ?? `vue-step-${index}`}>
      <Stack gap="sm">
        <Group gap="xs" align="baseline">
          <Badge size="lg" variant="filled">
            {index}
          </Badge>
          <Title order={5}>{title}</Title>
        </Group>
        {description ? (
          <Text size="sm" c="dimmed">
            {description}
          </Text>
        ) : null}
        {children}
      </Stack>
    </Paper>
  );
}

export function VueOnboardingFlow({ dsn, pingState }: Props) {
  const { t } = useTranslation();
  const entry = vueEntry();
  const files = entry.initFiles ?? [];
  const extras = ('extras' in entry ? entry.extras : undefined) as
    | {
        recommendedArchitecture: {
          description: string;
          files: ReadonlyArray<{ path: string; lang: string; contents: string }>;
        };
        verificationChecklist: ReadonlyArray<ChecklistItem>;
      }
    | undefined;

  const checklistItems: readonly ChecklistItem[] = extras?.verificationChecklist ?? [];
  const recommended = extras?.recommendedArchitecture;
  const eventReceived = pingState.result?.ok === true;

  // 7-step layout per issue #2: install, env, installer, mount, instrument, verify,
  // optional boundary. Step contents are derived from SDK_CATALOG + recommended-arch
  // extras so future copy/structure changes happen in one place (the catalog).
  const envFile = files.find((f) => f.path.endsWith('.env.local'));
  const installerFile = files.find((f) => f.path.endsWith('arguslog.ts'));
  const mainFile = files.find((f) => f.path.endsWith('main.ts'));
  const wrapSnippet = 'wrapSnippet' in entry ? entry.wrapSnippet : null;
  const telemetryFile = recommended?.files[0];

  return (
    <Grid gutter="md">
      <Grid.Col span={{ base: 12, md: 8 }}>
        <Stack gap="md">
          <Alert variant="light" color="blue" icon={<IconBolt size={16} />}>
            <Text size="sm">
              <strong>Workflow-first onboarding.</strong> Walk these 7 steps end-to-end — the
              install plus one instrumented action gives you trustworthy telemetry, not just a
              synthetic crash.
            </Text>
          </Alert>

          <Step
            index={1}
            title="Install the SDK"
            description="Adds `@arguslog/sdk-vue` plus the underlying browser SDK as a transitive dep."
          >
            <Code block style={{ fontSize: 13 }}>
              {entry.installCmd}
            </Code>
          </Step>

          {envFile ? (
            <Step
              index={2}
              title="Configure env vars"
              description="DSN lives in `.env.local` so it stays out of app code and version control. Vite injects it at build time."
            >
              <FileCard
                path={envFile.path}
                lang={envFile.lang}
                contents={inlineDsn(envFile.contents, dsn)}
              />
            </Step>
          ) : null}

          {installerFile ? (
            <Step
              index={3}
              title="Create the installer module"
              description="Named installer that reads the DSN at build time and no-ops cleanly when missing (safe for local dev without keys)."
            >
              <FileCard
                path={installerFile.path}
                lang={installerFile.lang}
                contents={installerFile.contents}
              />
            </Step>
          ) : null}

          {mainFile ? (
            <Step
              index={4}
              title="Wire it into your app entry"
              description="A single line in `main.ts` between `createApp` and `mount`."
            >
              <FileCard path={mainFile.path} lang={mainFile.lang} contents={mainFile.contents} />
            </Step>
          ) : null}

          {recommended && telemetryFile ? (
            <Step
              index={5}
              title="Instrument one real workflow"
              description={recommended.description}
            >
              <FileCard
                path={telemetryFile.path}
                lang={telemetryFile.lang}
                contents={telemetryFile.contents}
              />
            </Step>
          ) : null}

          <Step
            index={6}
            title="Verify the wire path"
            description="Click below to POST a synthetic event through the same path your SDK uses. Success means browser → ingest works end-to-end for this project."
          >
            <Group gap="xs" align="center">
              <Button
                onClick={pingState.onPing}
                loading={pingState.isPending}
                disabled={!dsn || pingState.isPending}
                data-testid="vue-step-verify-button"
              >
                {t('connect.testPing.button', { defaultValue: 'Send test event' })}
              </Button>
              {pingState.result ? (
                <Alert
                  variant="light"
                  color={pingState.result.ok ? 'teal' : 'red'}
                  data-testid="vue-step-verify-result"
                  style={{ flex: 1 }}
                >
                  <Text size="sm">{pingState.result.detail}</Text>
                </Alert>
              ) : null}
            </Group>
          </Step>

          {wrapSnippet ? (
            <Step
              index={7}
              title="Optional: add an error boundary"
              description="Render a friendly fallback on render errors. Required `:fallback` prop — the slot syntax used to be documented but never bound at runtime."
            >
              <FileCard path="src/App.vue" lang="vue" contents={wrapSnippet} />
            </Step>
          ) : null}
        </Stack>
      </Grid.Col>

      <Grid.Col span={{ base: 12, md: 4 }}>
        <Stack gap="md" pos="sticky" top={16}>
          {checklistItems.length > 0 ? (
            <PostInstallChecklist
              items={checklistItems}
              eventReceived={eventReceived}
              autoTickOnEventId="event"
            />
          ) : null}
        </Stack>
      </Grid.Col>
    </Grid>
  );
}
