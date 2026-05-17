/**
 * Generic workflow-first onboarding panel — drives the Connect screen's per-SDK
 * "5-to-8 numbered steps + verification checklist" experience from a single
 * `SDK_CATALOG` entry.
 *
 * The component is intentionally data-driven: every step's title, description,
 * and content is derived from the catalog entry (`installCmd`, `initFiles[]`,
 * `wrapSnippet`, `extras.recommendedArchitecture`, `extras.verificationChecklist`).
 * Adding a new SDK to the workflow-first flow is therefore "add an entry to the
 * catalog + pass the slug here in ConnectProjectPage" — no per-SDK component
 * needed.
 *
 * Each `initFiles[]` entry may carry optional `stepTitle` + `stepDescription`
 * overrides; absent values fall back to defaults derived from the file path
 * (`.env*` → "Configure env vars", `*installer*` / `arguslog.ts` → "Create the
 * installer module", `main.ts` / `main.tsx` / `index.ts` → "Wire it into your
 * app entry"). The Vue + React entries both rely on these defaults for now;
 * exotic shapes (Next.js dual server+client) can override per-file.
 *
 * Step 6-ish ("Verify the wire path") is the only step the component owns
 * directly — it wires the parent's existing test-ping mutation into a button
 * + status alert and auto-ticks the matching checklist item on success.
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
import { IconBolt, IconCheck, IconCopy } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

import { SDK_CATALOG } from '../../lib/connectSnippets';

import { PostInstallChecklist, type ChecklistItem } from './PostInstallChecklist';

interface InitFile {
  path: string;
  lang: string;
  contents: string;
  stepTitle?: string;
  stepDescription?: string;
}

interface RecommendedArchitecture {
  description: string;
  files: ReadonlyArray<{ path: string; lang: string; contents: string }>;
}

interface CatalogExtras {
  recommendedArchitecture?: RecommendedArchitecture;
  verificationChecklist?: ReadonlyArray<ChecklistItem>;
}

interface Props {
  /** SDK_CATALOG slug — e.g. `'vue'`, `'react'`, `'nextjs'`. */
  slug: string;
  /** Real DSN to inline into env-file step. Falsy → keep `<DSN>` placeholder. */
  dsn: string | null;
  /** Existing test-event ping plumbing — wired to the verify step. */
  pingState: {
    onPing: () => void;
    isPending: boolean;
    /** null = not yet attempted; ok=true success; ok=false failure */
    result: { ok: boolean; detail: string } | null;
  };
  /**
   * Path to render in the wrap-snippet step (where the SDK ships an error
   * boundary). Defaults to a sensible per-language guess but the caller can
   * override for accuracy (Vue → `src/App.vue`, React → `src/main.tsx`, etc.).
   */
  wrapPath?: string;
}

function entryForSlug(slug: string) {
  const entry = SDK_CATALOG.find((p) => p.slug === slug);
  if (!entry || !('initFiles' in entry) || !entry.initFiles) {
    throw new Error(
      `OnboardingFlow: SDK_CATALOG has no entry with slug="${slug}" carrying initFiles[].`,
    );
  }
  return entry;
}

function inlineDsn(contents: string, dsn: string | null): string {
  if (!dsn) return contents;
  return contents.replaceAll('<DSN>', dsn);
}

function defaultTitleFor(path: string): string {
  const base = path.split('/').pop() ?? path;
  if (base.startsWith('.env')) return 'Configure env vars';
  if (base.includes('arguslog')) return 'Create the installer module';
  if (base === 'main.ts' || base === 'main.tsx' || base === 'index.ts' || base === 'index.tsx')
    return 'Wire it into your app entry';
  if (base.includes('instrumentation')) return 'Wire server-side instrumentation';
  if (base.includes('layout')) return 'Wrap your app shell';
  if (base.includes('app.config')) return 'Register the Arguslog provider';
  if (base.includes('environment')) return 'Configure environment';
  return `Create \`${path}\``;
}

function defaultDescriptionFor(path: string): string | undefined {
  const base = path.split('/').pop() ?? path;
  if (base.startsWith('.env'))
    return 'DSN lives in env so it stays out of app code and version control. The framework injects it at build time.';
  if (base.includes('arguslog'))
    return 'Named installer that reads the DSN at build time and no-ops cleanly when missing (safe for local dev without keys).';
  if (base === 'main.ts' || base === 'main.tsx' || base === 'index.ts' || base === 'index.tsx')
    return 'A single line between app creation and mount.';
  return undefined;
}

function FileCard({
  path,
  contents,
  lang,
  slug,
}: {
  path: string;
  contents: string;
  lang?: string;
  slug: string;
}) {
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
              data-testid={`onboarding-copy-${slug}-${path}`}
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
}: {
  index: number;
  title: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <Paper p="md" withBorder radius="md" data-testid={`onboarding-step-${index}`}>
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

export function OnboardingFlow({ slug, dsn, pingState, wrapPath }: Props) {
  const { t } = useTranslation();
  const entry = entryForSlug(slug);
  const files = (entry.initFiles ?? []) as ReadonlyArray<InitFile>;
  const extras = ('extras' in entry ? (entry.extras as CatalogExtras) : undefined) ?? {};
  const wrapSnippet = 'wrapSnippet' in entry ? entry.wrapSnippet : null;
  const recommended = extras.recommendedArchitecture;
  const checklistItems = extras.verificationChecklist ?? [];
  const eventReceived = pingState.result?.ok === true;
  const telemetryFile = recommended?.files[0];

  // Compose the step list once so each step can claim its 1-based index dynamically
  // rather than hard-coding 1..7. The order is: Install → each initFiles entry →
  // recommended-architecture (if present) → Verify → wrap (if present).
  let stepIndex = 0;
  const nextIndex = () => ++stepIndex;

  return (
    <Grid gutter="md">
      <Grid.Col span={{ base: 12, md: 8 }}>
        <Stack gap="md">
          <Alert
            variant="light"
            color="blue"
            icon={<IconBolt size={16} />}
            data-testid="onboarding-intro"
          >
            <Text size="sm">
              <strong>Workflow-first onboarding.</strong> Walk these steps end-to-end — the install
              plus one instrumented action gives you trustworthy telemetry, not just a synthetic
              crash.
            </Text>
          </Alert>

          <Step
            index={nextIndex()}
            title="Install the SDK"
            description={`Adds \`${entry.pkg}\` to your project.`}
          >
            <Code block style={{ fontSize: 13 }}>
              {entry.installCmd}
            </Code>
          </Step>

          {files.map((file) => (
            <Step
              key={file.path}
              index={nextIndex()}
              title={file.stepTitle ?? defaultTitleFor(file.path)}
              description={file.stepDescription ?? defaultDescriptionFor(file.path)}
            >
              <FileCard
                path={file.path}
                lang={file.lang}
                contents={inlineDsn(file.contents, dsn)}
                slug={slug}
              />
            </Step>
          ))}

          {recommended && telemetryFile ? (
            <Step
              index={nextIndex()}
              title="Instrument one real workflow"
              description={recommended.description}
            >
              <FileCard
                path={telemetryFile.path}
                lang={telemetryFile.lang}
                contents={telemetryFile.contents}
                slug={slug}
              />
            </Step>
          ) : null}

          <Step
            index={nextIndex()}
            title="Verify the wire path"
            description="Click below to POST a synthetic event through the same path your SDK uses. Success means browser → ingest works end-to-end for this project."
          >
            <Group gap="xs" align="center">
              <Button
                onClick={pingState.onPing}
                loading={pingState.isPending}
                disabled={!dsn || pingState.isPending}
                data-testid="onboarding-verify-button"
              >
                {t('connect.testPing.button', { defaultValue: 'Send test event' })}
              </Button>
              {pingState.result ? (
                <Alert
                  variant="light"
                  color={pingState.result.ok ? 'teal' : 'red'}
                  data-testid="onboarding-verify-result"
                  style={{ flex: 1 }}
                >
                  <Text size="sm">{pingState.result.detail}</Text>
                </Alert>
              ) : null}
            </Group>
          </Step>

          {wrapSnippet ? (
            <Step
              index={nextIndex()}
              title="Optional: add an error boundary"
              description="Render a friendly fallback on render errors so transient failures recover without a full page reload."
            >
              <FileCard
                path={wrapPath ?? defaultWrapPath(slug)}
                lang={defaultWrapLang(slug)}
                contents={wrapSnippet}
                slug={slug}
              />
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

function defaultWrapPath(slug: string): string {
  switch (slug) {
    case 'vue':
      return 'src/App.vue';
    case 'react':
      return 'src/main.tsx';
    case 'nextjs':
      return 'app/layout.tsx';
    case 'react-native':
      return 'App.tsx';
    default:
      return 'src/App.tsx';
  }
}

function defaultWrapLang(slug: string): string {
  switch (slug) {
    case 'vue':
      return 'vue';
    case 'react':
    case 'nextjs':
    case 'react-native':
      return 'tsx';
    default:
      return 'tsx';
  }
}
