/**
 * Pure snippet generator for the Connect-Project wizard. Given a project DSN, an optional PAT,
 * and the API base URL, produces ready-to-paste config / code snippets for every supported
 * client. Pure on purpose so the wizard page renders deterministically and tests can pin the
 * exact strings.
 *
 * Conventions:
 *   - SDK snippets use the DSN (project-scoped, ingest-only).
 *   - MCP and CLI snippets use the PAT (user-scoped, server-to-server).
 *   - When the caller hasn't minted a PAT yet, `pat` is null and those snippets ship a
 *     `<GENERATE_PAT_FIRST>` placeholder so the user can still copy the structure.
 */

export type SnippetGroup = 'agent' | 'workflow' | 'sdk' | 'mcp' | 'cli';

/**
 * "Read · Eval · Triage · Loop" workflows — exact mirror of the catalog the MCP server
 * exposes via `prompts/list` (see `packages/mcp-server/src/prompts.ts`). Bodies are duplicated
 * in TypeScript form on both sides; a snapshot regression test on the web side asserts the
 * Markdown comes out identical for a frozen args fixture, so drift between server and dashboard
 * is caught in CI rather than at runtime. Keeping the catalog inline (instead of pulling it
 * into a shared workspace package) avoids growing @arguslog/sdk-core out of its current
 * transport/scope/scrubber scope.
 */
export const WORKFLOWS = [
  {
    id: 'workflow-triage-loop',
    name: 'arguslog_triage_loop',
    client: 'Triage loop',
    description:
      'Walk the unresolved issue queue one item at a time. For each, propose an action, wait for the user, apply via MCP tools. Operationalises the "Loop" half of the slogan.',
    body: `You are running the Arguslog triage loop for project <PROJECT_ID>.

Goal: keep the unresolved queue moving. Walk issues one at a time; for each one suggest an action; apply only after the user confirms.

**Step 1 — fetch the batch.** Call \`list_issues\` with:
\`\`\`json
{ "projectId": <PROJECT_ID>, "status": "unresolved", "sort": "lastSeenAt:desc", "limit": 10 }
\`\`\`

**Step 2 — walk each issue.** For every result: print one line (\`#<id> · <title> · level=<level> · count=<count> · lastSeen=<lastSeenAt> · assignee=<assigneeUserId or "—">\`), then \`get_issue\` for full detail. Propose ONE action: \`assign_issue\` to a likely owner, \`triage_issue\` → resolved if duplicate, \`triage_issue\` to set firstSeenRelease, or skip. **Wait for "ok"** before applying the matching MCP tool.

**Step 3 — report.** After the batch print counts (triaged / skipped / errored) and ask whether to fetch the next batch.

**Stop conditions**: the user says "stop", \`list_issues\` returns empty, or two consecutive MCP errors.

Never invent issue ids — only act on data fetched this session.`,
  },
  {
    id: 'workflow-release-postmortem',
    name: 'arguslog_release_postmortem',
    client: 'Release postmortem',
    description:
      'Auto-generate a Markdown postmortem for issues first seen in a given release. Groups by stack-frame fingerprint, hypothesises root cause, recommends actions. Read-only — never mutates issues.',
    body: `You are writing a release postmortem for project <PROJECT_ID>, release \`<VERSION>\`.

**Step 1 — resolve the release.** Call \`list_release\` with \`{ projectId: <PROJECT_ID> }\`; capture the id whose version equals \`<VERSION>\`. If no match, stop and tell the user.

**Step 2 — fetch issues introduced.** \`list_issues\` with \`{ projectId: <PROJECT_ID>, firstSeenReleaseId: <releaseId>, limit: 50 }\`.

**Step 3 — detail.** For each (cap 25), \`get_issue\` and capture title, level, count, lastSeen, top stack frame, and the latest event's exception message.

**Step 4 — group by stack-frame fingerprint.** Issues sharing a top frame are likely one root cause.

**Step 5 — write Markdown postmortem** with sections: Title (\`# Postmortem — <VERSION>\`), summary (released date, issue count, severity mix), top regressions (max 5 with hypothesis + recommended action), timeline, recommended next steps.

**Step 6 — save.** If \`docs/postmortems/\` exists, write \`docs/postmortems/<VERSION>.md\`; otherwise print to chat.

Read-only — do not call any mutating MCP tools (no \`triage_issue\`, no \`assign_issue\`).`,
  },
  {
    id: 'workflow-regression-check',
    name: 'arguslog_regression_check',
    client: 'Regression check',
    description:
      'Diff the current release against the previous one — surfaces issues that are new or spiking. Pairs each finding with stack frames + git blame.',
    body: `You are running a regression check for project <PROJECT_ID>: \`<PREVIOUS_VERSION>\` → \`<CURRENT_VERSION>\`.

**Step 1 — resolve both releases.** \`list_release\` → capture ids for \`<CURRENT_VERSION>\` and \`<PREVIOUS_VERSION>\`. If either missing, stop.

**Step 2 — fetch.** \`list_issues\` twice: new-in-current (filter by \`firstSeenReleaseId\`); active-in-previous (filter by \`seenInReleaseId\`).

**Step 3 — classify.** NEW: only in current. SPIKING: in both but current count ≥3× previous count.

**Step 4 — detail + blame.** For each finding (cap 15), \`get_issue\` for top frame; if \`.git/\` exists, run \`git blame -L <line>,<line> <file>\` for the top frame and capture commit hash + author.

**Step 5 — report** in a Markdown table: \`| Issue | Status | Count(new/prev) | Top frame | Likely author |\`. Below the table, suggest rollback / assign / hotfix as appropriate.

Read-only by default. Only call \`triage_issue\` / \`assign_issue\` if the user explicitly says "apply".`,
  },
  {
    id: 'workflow-investigate-issue',
    name: 'arguslog_investigate_issue',
    client: 'Investigate single issue',
    description:
      'Deep-dive a single issue: detail + recent events + breadcrumbs → root-cause hypothesis with file:line references → action proposal.',
    body: `You are investigating Arguslog issue #<ISSUE_ID> in project <PROJECT_ID>.

**Step 1 — fetch detail.** \`get_issue\` with \`{ projectId: <PROJECT_ID>, issueId: <ISSUE_ID> }\`. Capture title, level, count, firstSeenAt, lastSeenAt, current assignee, top stack frame.

**Step 2 — recent events.** \`list_issue_events\` with \`{ projectId: <PROJECT_ID>, issueId: <ISSUE_ID>, limit: 5 }\`. Inspect exception chain, breadcrumbs (HTTP, console, navigation), request context.

**Step 3 — hypothesise root cause.** Show the throwing line (\`<file>:<line> · <function>\`), one-sentence hypothesis using breadcrumbs + exception chain, file-level evidence if the repo is checked out, and a reproduction hint from breadcrumbs.

**Step 4 — propose action.** Choose ONE: fix suggestion with a diff for review, assign to likely owner from \`git blame\`, resolve as duplicate, or mark as not-a-bug.

**Step 5 — wait for user.** Ask "what would you like to do?". Only call \`triage_issue\` / \`assign_issue\` after explicit confirmation.`,
  },
] as const;

export interface ConnectSnippet {
  /** Stable id — used for tab keys, test selectors, copy-button telemetry. */
  id: string;
  group: SnippetGroup;
  /** Display label for the tab. */
  client: string;
  /** Mantine `<Prism>` / `<Code>` language hint for syntax highlighting. */
  language: 'tsx' | 'ts' | 'js' | 'python' | 'java' | 'json' | 'bash' | 'markdown';
  /** One-line context shown above the code block. */
  description: string;
  /** The literal code to paste. */
  code: string;
}

/**
 * Pinned, in-sync mirror of `R__platforms_catalog.sql`. The magic-prompt builder needs to inline
 * exact `pkg@version` strings into the agent instructions so the LLM doesn't guess a stale or
 * non-existent version. `connectSnippets.platforms.parity.test.ts` keeps this honest by reading
 * the SQL migration and asserting (slug, pkg, version) match. `installCmd` codifies the
 * canonical install incantation per ecosystem so the prompt is one-paste-installable.
 */
export const SDK_CATALOG = [
  {
    slug: 'javascript',
    pkg: '@arguslog/sdk-browser',
    version: '2.0.1',
    installCmd: 'npm install @arguslog/sdk-browser@^2',
    detect: 'package.json without a framework — vanilla HTML/JS or tooling-free bundler',
    entryFile: 'src/main.js or the first script loaded by index.html',
    lang: 'ts',
    initSnippet: `import { init } from '@arguslog/sdk-browser';

init({
  dsn: '<DSN>',
  environment: 'production',
  integrations: ['globalHandlers', 'autoBreadcrumbs'],
});`,
    wrapSnippet: null,
  },
  {
    slug: 'react',
    pkg: '@arguslog/sdk-react',
    version: '2.0.2',
    installCmd: 'npm install @arguslog/sdk-react@^2',
    detect: 'package.json contains "react"',
    entryFile: 'src/main.tsx + src/arguslog.ts + .env.local (env-driven installer)',
    lang: 'tsx',
    // Same env-driven 3-file shape Vue uses. Vite is the primary target — CRA is documented
    // in the SDK README as a deprecated alternative using `REACT_APP_*` env vars instead.
    initSnippet: '// See `initFiles` below — React ships a 3-file env-driven installer shape.',
    initFiles: [
      {
        path: '.env.local',
        lang: 'bash',
        contents: `# Vite picks this up automatically; do NOT commit a real DSN here.
VITE_ARGUSLOG_DSN=<DSN>
VITE_APP_RELEASE=1.0.0`,
      },
      {
        path: 'src/arguslog.ts',
        lang: 'ts',
        contents: `import { init } from '@arguslog/sdk-react';

let installed = false;

/**
 * Install Arguslog once at app boot. Reads the DSN from VITE_ARGUSLOG_DSN at
 * build time. If the variable is missing (local dev without keys), the installer
 * is a deliberate no-op so the app boots cleanly without Arguslog mounted.
 */
export function installArguslog(): void {
  if (installed) return;
  const dsn = import.meta.env.VITE_ARGUSLOG_DSN;
  if (!dsn) return;

  init({
    dsn,
    environment: import.meta.env.MODE,
    release: import.meta.env.VITE_APP_RELEASE,
    integrations: ['globalHandlers', 'autoBreadcrumbs'],
  });
  installed = true;
}`,
      },
      {
        path: 'src/main.tsx',
        lang: 'tsx',
        contents: `import { createRoot } from 'react-dom/client';
import { ArguslogErrorBoundary } from '@arguslog/sdk-react';

import App from './App';
import { installArguslog } from './arguslog';

installArguslog();

createRoot(document.getElementById('root')!).render(
  <ArguslogErrorBoundary fallback={<p>Something went wrong.</p>}>
    <App />
  </ArguslogErrorBoundary>,
);`,
      },
    ],
    wrapSnippet: null,
    extras: {
      recommendedArchitecture: {
        description:
          'The best React onboarding moment is not "the app crashed" — it is "I can see telemetry around a real user action." Wrap your domain calls in a tiny telemetry service so a single workflow lights up attempt → success → validation/unexpected failure paths in Arguslog. Useful operationally, not just technically.',
        files: [
          {
            path: 'src/services/telemetry.ts',
            lang: 'ts',
            contents: `import { addBreadcrumb, captureException, captureMessage } from '@arguslog/sdk-react';

/**
 * One named breadcrumb / event per workflow phase. Instrument a single real action
 * (checkout, create todo, save form) end-to-end so the next dashboard visit shows
 * the user journey, not just stack traces.
 */
export const telemetry = {
  attempt: (action: string) =>
    addBreadcrumb({ category: 'workflow', message: \`\${action}:attempt\`, level: 'info' }),
  success: (action: string) =>
    addBreadcrumb({ category: 'workflow', message: \`\${action}:success\`, level: 'info' }),
  validation: (action: string, err: Error) =>
    captureMessage(\`\${action} validation failed: \${err.message}\`, 'warning'),
  unexpected: (action: string, err: Error) =>
    captureException(err, { tags: { action } }),
};`,
          },
        ],
      },
      verificationChecklist: [
        { id: 'package', label: 'SDK installed (`@arguslog/sdk-react` in dependencies)' },
        {
          id: 'env',
          label:
            '`VITE_ARGUSLOG_DSN` set in `.env.local` (Vite) — or `REACT_APP_ARGUSLOG_DSN` (CRA)',
        },
        { id: 'installer', label: '`installArguslog()` wired in `src/main.tsx`' },
        {
          id: 'boundary',
          label: '`<ArguslogErrorBoundary fallback={...}>` wraps the React root',
        },
        { id: 'workflow', label: 'One real user workflow instrumented with `telemetry.*`' },
        { id: 'event', label: 'Test event received in the dashboard (verify step below)' },
        {
          id: 'failure',
          label: 'One controlled failure path exercised (validation or unexpected)',
        },
      ],
    },
  },
  {
    slug: 'angular',
    pkg: '@arguslog/sdk-angular',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-angular@^2',
    detect: 'package.json contains "@angular/core"',
    entryFile: 'src/environments/*.ts + src/app/app.config.ts (env-driven provider)',
    lang: 'ts',
    // Angular's idiomatic env-config story is the stock `environment.ts` +
    // `environment.production.ts` files swapped by the build via `fileReplacements` in
    // angular.json. provideArguslog reads from these — no `@ngx-env/builder` or extra dep
    // required. The provider is itself a no-op when `dsn` is empty/undefined so local dev
    // without keys boots cleanly.
    initSnippet: '// See `initFiles` below — Angular ships an environment-driven provider shape.',
    initFiles: [
      {
        path: 'src/environments/environment.ts',
        lang: 'ts',
        contents: `// Base / dev config. Empty DSN → provideArguslog() is a no-op for local dev.
// Override per-build via angular.json fileReplacements (production.ts below).
export const environment = {
  production: false,
  arguslogDsn: '',
  arguslogRelease: '',
};`,
      },
      {
        path: 'src/environments/environment.production.ts',
        lang: 'ts',
        contents: `// Production overlay — angular.json fileReplacements swaps this in for prod builds.
// DO NOT commit a real DSN here; CI should inject it from secrets at build time.
export const environment = {
  production: true,
  arguslogDsn: '<DSN>',
  arguslogRelease: '1.0.0',
};`,
      },
      {
        path: 'src/app/app.config.ts',
        lang: 'ts',
        contents: `import { ApplicationConfig } from '@angular/core';
import { provideArguslog } from '@arguslog/sdk-angular';

import { environment } from '../environments/environment';

export const appConfig: ApplicationConfig = {
  providers: [
    // Spread guards the provider from being wired at all when DSN is missing — keeps
    // local dev quiet without resorting to runtime branching inside Arguslog.
    ...(environment.arguslogDsn
      ? [
          provideArguslog({
            dsn: environment.arguslogDsn,
            environment: environment.production ? 'production' : 'development',
            release: environment.arguslogRelease,
            integrations: ['globalHandlers', 'autoBreadcrumbs'],
          }),
        ]
      : []),
    // ...your other providers
  ],
};`,
      },
    ],
    wrapSnippet: null,
    extras: {
      recommendedArchitecture: {
        description:
          'Angular has no UI-level error boundary — the SDK auto-wires the framework `ErrorHandler` so render-time errors are captured automatically. The most useful onboarding moment is therefore "I can see breadcrumbs and events from a real domain action." Inject a small TelemetryService and call it from your components / effects to light up attempt → success → validation/unexpected paths in Arguslog.',
        files: [
          {
            path: 'src/app/services/telemetry.service.ts',
            lang: 'ts',
            contents: `import { Injectable } from '@angular/core';
import {
  addBreadcrumb,
  captureException,
  captureMessage,
} from '@arguslog/sdk-angular';

/**
 * Inject anywhere a domain action runs. The methods are no-ops if Arguslog
 * was not initialised (e.g. local dev without a DSN) — safe to call.
 */
@Injectable({ providedIn: 'root' })
export class TelemetryService {
  attempt(action: string): void {
    addBreadcrumb({ category: 'workflow', message: \`\${action}:attempt\`, level: 'info' });
  }
  success(action: string): void {
    addBreadcrumb({ category: 'workflow', message: \`\${action}:success\`, level: 'info' });
  }
  validation(action: string, err: Error): void {
    captureMessage(\`\${action} validation failed: \${err.message}\`, 'warning');
  }
  unexpected(action: string, err: Error): void {
    captureException(err, { tags: { action } });
  }
}`,
          },
        ],
      },
      verificationChecklist: [
        { id: 'package', label: 'SDK installed (`@arguslog/sdk-angular` in dependencies)' },
        {
          id: 'env',
          label: '`arguslogDsn` set in `environment.production.ts` (or your env injector)',
        },
        { id: 'installer', label: '`provideArguslog` wired in `app.config.ts` providers array' },
        {
          id: 'error-handler',
          label: 'Default Angular `ErrorHandler` replaced (auto-wired by `provideArguslog`)',
        },
        { id: 'workflow', label: 'One real user workflow instrumented via `TelemetryService`' },
        { id: 'event', label: 'Test event received in the dashboard (verify step below)' },
        {
          id: 'failure',
          label: 'One controlled failure path exercised (validation or unexpected)',
        },
      ],
    },
  },
  {
    slug: 'vue',
    pkg: '@arguslog/sdk-vue',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-vue@^2',
    detect: 'package.json contains "vue" (>= 3.x)',
    entryFile: 'src/main.ts + src/arguslog.ts + .env.local (env-driven installer)',
    lang: 'ts',
    // Vue is the only SDK that ships a multi-file install shape: env vars for the DSN, an
    // installer module that no-ops when DSN is missing, and a tiny main.ts that wires it in.
    // The agent renders each as a labelled fenced block; `initSnippet` is kept as a one-line
    // pointer so legacy single-file consumers still see something meaningful.
    initSnippet: '// See `initFiles` below — Vue ships a 3-file env-driven installer shape.',
    initFiles: [
      {
        path: '.env.local',
        lang: 'bash',
        contents: `# Vite picks this up automatically; do NOT commit a real DSN here.
VITE_ARGUSLOG_DSN=<DSN>
VITE_APP_RELEASE=1.0.0`,
      },
      {
        path: 'src/arguslog.ts',
        lang: 'ts',
        contents: `import type { App as VueApp } from 'vue';
import { createArguslog } from '@arguslog/sdk-vue';

/**
 * Install Arguslog into the Vue app. Reads the DSN from VITE_ARGUSLOG_DSN at build
 * time. If the variable is missing (local dev without keys), the installer is a
 * deliberate no-op so the app boots cleanly without Arguslog mounted.
 */
export function installArguslog(app: VueApp): void {
  const dsn = import.meta.env.VITE_ARGUSLOG_DSN;
  if (!dsn) return;

  app.use(
    createArguslog({
      dsn,
      environment: import.meta.env.MODE,
      release: import.meta.env.VITE_APP_RELEASE,
      integrations: ['globalHandlers', 'autoBreadcrumbs'],
    }),
  );
}`,
      },
      {
        path: 'src/main.ts',
        lang: 'ts',
        contents: `import { createApp } from 'vue';

import App from './App.vue';
import { installArguslog } from './arguslog';

const app = createApp(App);
installArguslog(app);
app.mount('#app');`,
      },
    ],
    wrapSnippet: `<!-- Optional: wrap routed content to render a friendly fallback on render errors.
     ArguslogErrorBoundary requires the \`fallback\` prop (a VNode or render fn). -->
<template>
  <ArguslogErrorBoundary :fallback="errorFallback">
    <RouterView />
  </ArguslogErrorBoundary>
</template>

<script setup lang="ts">
import { h } from 'vue';
import { ArguslogErrorBoundary } from '@arguslog/sdk-vue';

const errorFallback = ({ error, reset }: { error: Error; reset: () => void }) =>
  h('div', { class: 'error-state' }, [
    h('p', \`Something went wrong: \${error.message}\`),
    h('button', { onClick: reset }, 'Try again'),
  ]);
</script>`,
    // Workflow-first onboarding extras (Phase B). Optional content the Connect screen surfaces
    // alongside the install snippets — a recommended telemetry-service shape and a post-install
    // verification checklist. Lives on the Vue entry only; other SDKs may grow this later.
    extras: {
      recommendedArchitecture: {
        description:
          'The best Vue onboarding moment is not "the app crashed" — it is "I can see telemetry around a real user action." Wrap your domain calls in a tiny telemetry service so a single workflow lights up attempt → success → validation/unexpected failure paths in Arguslog. Useful operationally, not just technically.',
        files: [
          {
            path: 'src/services/telemetry.ts',
            lang: 'ts',
            contents: `import { useArguslog } from '@arguslog/sdk-vue';

/**
 * One named breadcrumb / event per workflow phase. Instrument a single real action
 * (checkout, create todo, save form) end-to-end so the next dashboard visit shows
 * the user journey, not just stack traces.
 */
export const telemetry = {
  attempt: (action: string) =>
    useArguslog().addBreadcrumb({
      category: 'workflow',
      message: \`\${action}:attempt\`,
      level: 'info',
    }),
  success: (action: string) =>
    useArguslog().addBreadcrumb({
      category: 'workflow',
      message: \`\${action}:success\`,
      level: 'info',
    }),
  validation: (action: string, err: Error) =>
    useArguslog().captureMessage(
      \`\${action} validation failed: \${err.message}\`,
      'warning',
    ),
  unexpected: (action: string, err: Error) =>
    useArguslog().captureException(err, { tags: { action } }),
};`,
          },
        ],
      },
      verificationChecklist: [
        { id: 'package', label: 'SDK installed (`@arguslog/sdk-vue` in dependencies)' },
        {
          id: 'env',
          label: '`VITE_ARGUSLOG_DSN` set in `.env.local` (or your env injector)',
        },
        { id: 'installer', label: '`installArguslog(app)` wired in `src/main.ts`' },
        {
          id: 'boundary',
          label: 'Optional: `ArguslogErrorBoundary` placed at the app shell',
        },
        { id: 'workflow', label: 'One real user workflow instrumented with `telemetry.*`' },
        { id: 'event', label: 'Test event received in the dashboard (verify step below)' },
        {
          id: 'failure',
          label: 'One controlled failure path exercised (validation or unexpected)',
        },
      ],
    },
  },
  {
    slug: 'nextjs',
    pkg: '@arguslog/sdk-nextjs',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-nextjs@^2',
    detect: 'package.json contains "next"',
    entryFile: 'instrumentation.ts (server) + app/layout.tsx (client) + .env.local',
    lang: 'ts',
    // Next.js is dual-path: `instrumentation.ts` boots the Node SDK on the server, and
    // `app/layout.tsx` initialises the React/browser SDK + wraps the tree in the error
    // boundary on the client. We split these into 4 files so the agent (and a copy-pasting
    // operator) writes them at the correct paths instead of trying to cram both into one
    // file with a runtime guard.
    initSnippet:
      '// See `initFiles` below — Next.js ships a 4-file env-driven dual-path install shape.',
    initFiles: [
      {
        path: '.env.local',
        lang: 'bash',
        contents: `# Next.js auto-loads this; do NOT commit a real DSN here.
# Client-side bundle reads NEXT_PUBLIC_* at build time.
NEXT_PUBLIC_ARGUSLOG_DSN=<DSN>
# Server-side runtime reads bare process.env (Node SDK picks up ARGUSLOG_DSN
# natively when init({dsn}) is omitted).
ARGUSLOG_DSN=<DSN>
NEXT_PUBLIC_APP_RELEASE=1.0.0`,
      },
      {
        path: 'instrumentation.ts',
        lang: 'ts',
        contents: `// Next 13+ server instrumentation hook — runs once per server process boot.
// The runtime guard keeps the Node SDK off the Edge runtime (where it doesn't run).
export async function register() {
  if (process.env.NEXT_RUNTIME !== 'nodejs') return;
  const dsn = process.env.ARGUSLOG_DSN;
  if (!dsn) return; // no-op when DSN is missing — safe for local dev without keys

  const { init } = await import('@arguslog/sdk-nextjs/server');
  init({
    dsn,
    environment: process.env.NODE_ENV,
    release: process.env.NEXT_PUBLIC_APP_RELEASE,
    integrations: ['processHandlers', 'http'],
  });
}

// Re-export so Next.js can wire the App Router error hook automatically.
export { onRequestError } from '@arguslog/sdk-nextjs/server';`,
      },
      {
        path: 'app/arguslog.client.ts',
        lang: 'ts',
        contents: `'use client';

import { init } from '@arguslog/sdk-nextjs/client';

let installed = false;

/**
 * Install Arguslog in the browser bundle. Reads NEXT_PUBLIC_ARGUSLOG_DSN at
 * build time and no-ops when missing — safe for local dev without keys.
 */
export function installArguslog(): void {
  if (installed) return;
  const dsn = process.env.NEXT_PUBLIC_ARGUSLOG_DSN;
  if (!dsn) return;

  init({
    dsn,
    environment: process.env.NODE_ENV,
    release: process.env.NEXT_PUBLIC_APP_RELEASE,
    integrations: ['globalHandlers', 'autoBreadcrumbs'],
  });
  installed = true;
}`,
      },
      {
        path: 'app/layout.tsx',
        lang: 'tsx',
        contents: `import { ArguslogErrorBoundary } from '@arguslog/sdk-nextjs/client';

import { installArguslog } from './arguslog.client';

installArguslog();

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html>
      <body>
        <ArguslogErrorBoundary fallback={<p>Something went wrong.</p>}>
          {children}
        </ArguslogErrorBoundary>
      </body>
    </html>
  );
}`,
      },
    ],
    wrapSnippet: null,
    extras: {
      recommendedArchitecture: {
        description:
          'Next.js gives you two halves to instrument: server-side data fetching / Route Handlers (use the @arguslog/sdk-nextjs/server captureException) and client-side interactions (use the same telemetry shape via the /client subpath). One shared service keeps the call sites consistent across both runtimes.',
        files: [
          {
            path: 'lib/telemetry.ts',
            lang: 'ts',
            contents: `// Works in both server and client runtimes — Next.js bundles the matching subpath
// based on the import context. The functions are no-ops if init() didn't run.
import {
  addBreadcrumb,
  captureException,
  captureMessage,
} from '@arguslog/sdk-nextjs/client';

export const telemetry = {
  attempt: (action: string) =>
    addBreadcrumb({ category: 'workflow', message: \`\${action}:attempt\`, level: 'info' }),
  success: (action: string) =>
    addBreadcrumb({ category: 'workflow', message: \`\${action}:success\`, level: 'info' }),
  validation: (action: string, err: Error) =>
    captureMessage(\`\${action} validation failed: \${err.message}\`, 'warning'),
  unexpected: (action: string, err: Error) =>
    captureException(err, { tags: { action } }),
};`,
          },
        ],
      },
      verificationChecklist: [
        { id: 'package', label: 'SDK installed (`@arguslog/sdk-nextjs` in dependencies)' },
        {
          id: 'env',
          label: '`NEXT_PUBLIC_ARGUSLOG_DSN` + `ARGUSLOG_DSN` set in `.env.local`',
        },
        {
          id: 'server',
          label: 'Server `instrumentation.ts` wired at repo root with the runtime guard',
        },
        { id: 'installer', label: 'Client `installArguslog()` wired in `app/layout.tsx`' },
        {
          id: 'boundary',
          label: '`<ArguslogErrorBoundary fallback={...}>` wraps the root layout',
        },
        { id: 'workflow', label: 'One real user workflow instrumented with `telemetry.*`' },
        { id: 'event', label: 'Test event received in the dashboard (verify step below)' },
        {
          id: 'failure',
          label: 'One controlled failure path exercised (validation or unexpected)',
        },
      ],
    },
  },
  {
    slug: 'web3',
    pkg: '@arguslog/sdk-web3',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-web3@^2 @arguslog/sdk-browser@^2',
    detect: 'package.json contains "viem", "ethers", or "@solana/web3.js"',
    entryFile: 'wherever you currently init() the wallet client',
    lang: 'ts',
    initSnippet: `import { init } from '@arguslog/sdk-browser';
import { initWeb3 } from '@arguslog/sdk-web3';

// 1. Standard browser SDK init — covers uncaught JS errors + breadcrumbs.
init({
  dsn: '<DSN>',
  environment: 'production',
  integrations: ['globalHandlers', 'autoBreadcrumbs'],
});

// 2. Wrap your wallet / RPC client(s). initWeb3 auto-detects viem, ethers, and Solana
//    and returns each wrapped — pass whatever you currently hand to your dapp.
const { walletClient } = initWeb3({
  walletClient: /* your existing viem walletClient OR ethers signer */,
});`,
    wrapSnippet: null,
  },
  {
    slug: 'react-native',
    pkg: '@arguslog/sdk-react-native',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-react-native@^2',
    detect: 'package.json contains "react-native"',
    entryFile: '.env + src/arguslog.ts + App.tsx (Expo-driven installer)',
    lang: 'tsx',
    // Expo is the primary target — `EXPO_PUBLIC_*` env vars are picked up at build time
    // without extra babel config. For bare RN the same shape works via react-native-config
    // (see the SDK README for the bare-RN alternative).
    initSnippet:
      '// See `initFiles` below — React Native ships an Expo env-driven installer shape.',
    initFiles: [
      {
        path: '.env',
        lang: 'bash',
        contents: `# Expo loads EXPO_PUBLIC_* at build time; do NOT commit a real DSN here.
# For bare RN, see the SDK README — same pattern via react-native-config.
EXPO_PUBLIC_ARGUSLOG_DSN=<DSN>
EXPO_PUBLIC_APP_RELEASE=1.0.0`,
      },
      {
        path: 'src/arguslog.ts',
        lang: 'ts',
        contents: `import { init } from '@arguslog/sdk-react-native';

let installed = false;

/**
 * Install Arguslog once at app boot. Reads the DSN from EXPO_PUBLIC_ARGUSLOG_DSN
 * at build time. If the variable is missing (local dev without keys), the
 * installer is a deliberate no-op so the app boots cleanly without Arguslog
 * mounted. Native handlers (uncaught JS exceptions, native crashes) wire up
 * automatically when init() runs.
 */
export function installArguslog(): void {
  if (installed) return;
  const dsn = process.env.EXPO_PUBLIC_ARGUSLOG_DSN;
  if (!dsn) return;

  init({
    dsn,
    environment: __DEV__ ? 'development' : 'production',
    release: process.env.EXPO_PUBLIC_APP_RELEASE,
    integrations: ['globalHandlers'],
  });
  installed = true;
}`,
      },
      {
        path: 'App.tsx',
        lang: 'tsx',
        contents: `import { ArguslogErrorBoundary } from '@arguslog/sdk-react-native';

import { installArguslog } from './src/arguslog';
import { CrashScreen } from './src/components/CrashScreen';
import { RootNavigator } from './src/RootNavigator';

installArguslog();

export default function App() {
  return (
    <ArguslogErrorBoundary fallback={<CrashScreen />}>
      <RootNavigator />
    </ArguslogErrorBoundary>
  );
}`,
      },
    ],
    wrapSnippet: null,
    extras: {
      recommendedArchitecture: {
        description:
          'React Native ships a UI error boundary (already in the install above) plus the same telemetry-service pattern as React. The crash-screen fallback is the boundary; the telemetry service is what catches the non-crash flows — "validation failed", "retry succeeded", "background sync errored". Wire one real screen with it and you have actionable telemetry for the next bug-report cycle.',
        files: [
          {
            path: 'src/services/telemetry.ts',
            lang: 'ts',
            contents: `import {
  addBreadcrumb,
  captureException,
  captureMessage,
} from '@arguslog/sdk-react-native';

/**
 * One named breadcrumb / event per workflow phase. Instrument a single real
 * screen (checkout, sign-in, sync) end-to-end so the next crash report shows
 * the user journey, not just stack traces.
 */
export const telemetry = {
  attempt: (action: string) =>
    addBreadcrumb({ category: 'workflow', message: \`\${action}:attempt\`, level: 'info' }),
  success: (action: string) =>
    addBreadcrumb({ category: 'workflow', message: \`\${action}:success\`, level: 'info' }),
  validation: (action: string, err: Error) =>
    captureMessage(\`\${action} validation failed: \${err.message}\`, 'warning'),
  unexpected: (action: string, err: Error) =>
    captureException(err, { tags: { action } }),
};`,
          },
        ],
      },
      verificationChecklist: [
        { id: 'package', label: 'SDK installed (`@arguslog/sdk-react-native` in dependencies)' },
        {
          id: 'env',
          label: '`EXPO_PUBLIC_ARGUSLOG_DSN` set in `.env` (or `react-native-config` for bare RN)',
        },
        { id: 'installer', label: '`installArguslog()` wired in `App.tsx`' },
        {
          id: 'boundary',
          label: '`<ArguslogErrorBoundary fallback={<CrashScreen />}>` wraps the root navigator',
        },
        { id: 'workflow', label: 'One real user workflow instrumented with `telemetry.*`' },
        { id: 'event', label: 'Test event received in the dashboard (verify step below)' },
        {
          id: 'device',
          label: 'Onboarding verified on a physical device (not just simulator/emulator)',
        },
        {
          id: 'failure',
          label: 'One controlled failure path exercised (validation or unexpected)',
        },
      ],
    },
  },
  {
    slug: 'node',
    pkg: '@arguslog/sdk-node',
    version: '2.0.1',
    installCmd: 'npm install @arguslog/sdk-node@^2',
    detect: 'package.json with no frontend framework (Express, Fastify, plain Node, workers)',
    entryFile: 'the FIRST file your process loads (e.g., src/index.ts before any handler import)',
    lang: 'ts',
    initSnippet: `import { init, captureException } from '@arguslog/sdk-node';

init({
  dsn: '<DSN>',
  environment: process.env.NODE_ENV,
  release: process.env.GIT_SHA,
  integrations: ['processHandlers', 'http'],
});

process.on('unhandledRejection', (err) => captureException(err));`,
    wrapSnippet: null,
  },
  {
    slug: 'java-spring',
    pkg: 'org.arguslog:java-sdk',
    version: '2.0.0',
    installCmd:
      'add to build.gradle (implementation "org.arguslog:java-sdk:2.0.0") or pom.xml dependency block',
    detect: 'build.gradle / build.gradle.kts / pom.xml with Spring Boot starter',
    entryFile: 'src/main/resources/application.yml (arguslog.dsn property)',
    lang: 'yaml',
    initSnippet: `# src/main/resources/application.yml — Spring Boot autoconfig picks this up at startup.
arguslog:
  dsn: "<DSN>"
  environment: production
  release: \${GIT_SHA:dev}`,
    wrapSnippet: null,
  },
  {
    slug: 'python',
    pkg: 'arguslog',
    version: '2.0.2',
    installCmd: 'pip install "arguslog>=2,<3"  (or uv add arguslog>=2)',
    detect: 'pyproject.toml, requirements.txt, or setup.py',
    entryFile:
      'the application entry — Django wsgi.py / Flask app.py / FastAPI main.py / a worker boot script',
    lang: 'python',
    initSnippet: `import arguslog

arguslog.init(
    dsn="<DSN>",
    environment="production",
    install_excepthook=True,        # global sys.excepthook
    install_logging_handler=30,     # forward WARNING+ through Python logging
)`,
    wrapSnippet: null,
  },
] as const;

export type AgentTarget = 'claude-code' | 'cursor' | 'codex' | 'copilot' | 'windsurf' | 'continue';

/** Per-agent MCP config file location + install hint shown in the magic prompt. */
const AGENT_MCP_TARGETS: Record<AgentTarget, { name: string; configPath: string; note: string }> = {
  'claude-code': {
    name: 'Claude Code',
    configPath: '.mcp.json (project root) — or use `claude mcp add`',
    note: 'Project-level .mcp.json is checked into the repo and shared with teammates.',
  },
  cursor: {
    name: 'Cursor',
    configPath: '.cursor/mcp.json (workspace) — or ~/.cursor/mcp.json for user-wide',
    note: 'Cursor 0.50+ supports Streamable HTTP MCP servers directly.',
  },
  codex: {
    name: 'Codex',
    configPath: '~/.codex/config.toml (user) — or .codex/config.toml (project, trusted)',
    note: 'Codex uses TOML, not JSON. The CLI and the VS Code/IDE extension share the same config file.',
  },
  copilot: {
    name: 'GitHub Copilot',
    configPath: '.vscode/mcp.json (Copilot Chat in VS Code) AND .mcp.json (Copilot CLI)',
    note: 'GitHub Copilot CLI migrated from .vscode/mcp.json to .mcp.json (https://gh.io/copilotcli-mcpmigrate); the prompt writes both so the same install works for the VS Code extension and the gh CLI.',
  },
  windsurf: {
    name: 'Windsurf',
    configPath: '~/.codeium/windsurf/mcp_config.json',
    note: 'Windsurf (Codeium) uses `serverUrl` (not `url`) for HTTP-transport MCP servers and keeps config in its own Codeium folder, separate from Cursor.',
  },
  continue: {
    name: 'Continue',
    configPath: '.continue/mcpServers/<name>.yaml (workspace YAML; one file per server)',
    note: 'Continue 1.0+ reads each MCP server from its own YAML file under `.continue/mcpServers/`. The legacy `experimental.modelContextProtocolServers` array in `~/.continue/config.json` is deprecated.',
  },
};

export interface SnippetContext {
  /** Project DSN (full `arguslog://...` form). Null if no active DSN exists yet. */
  dsn: string | null;
  /** Personal Access Token plaintext, visible exactly once after creation. Null until minted. */
  pat: string | null;
  /** API base URL the consumer should hit. Self-hosted setups override the default. */
  apiUrl: string;
}

/**
 * Extract the parts of the DSN the curl recipe needs without pulling in sdk-core's full
 * parser (which we don't want to bundle into the dashboard). Returns sentinel placeholders
 * when the DSN is missing so the snippet still renders something the user can mentally
 * pattern-match against. Wire form: {@code arguslog://<publicKey>@<host[:port]>/api/<projectId>}.
 */
function dissectDsn(dsn: string | null): {
  ingestUrl: string;
  publicKey: string;
  projectId: string;
} {
  if (!dsn) {
    return {
      ingestUrl: '<INGEST_URL>',
      publicKey: '<PUBLIC_KEY>',
      projectId: '<PROJECT_ID>',
    };
  }
  // arguslog://<key>@<host>/api/<projectId>
  const m = dsn.match(/^arguslog:\/\/([^@]+)@([^/]+)\/api\/(\d+)/);
  if (!m) {
    return {
      ingestUrl: '<INGEST_URL>',
      publicKey: '<PUBLIC_KEY>',
      projectId: '<PROJECT_ID>',
    };
  }
  const [, publicKey, host, projectId] = m;
  const protocol =
    host!.startsWith('localhost') || host!.startsWith('127.') || host!.includes(':')
      ? 'http'
      : 'https';
  return {
    ingestUrl: `${protocol}://${host}`,
    publicKey: publicKey!,
    projectId: projectId!,
  };
}

const DSN_PLACEHOLDER = '<GENERATE_DSN_FIRST>';
const PAT_PLACEHOLDER = '<GENERATE_PAT_FIRST>';

function v(value: string | null, placeholder: string): string {
  return value ?? placeholder;
}

// ─── Agent magic-prompt builder ────────────────────────────────────────────────
// One pure function per markdown section so they compose, test, and read straight-through.
// The resulting prompt is paste-ready into Claude Code / Cursor / Codex / Copilot Chat — the
// agent reads it like an issue ticket and executes it. Stack detection runs at paste time on
// the user's actual repo, so the SPA-side `Platform` attribution is irrelevant here.

function agentRoleHeader(agent: AgentTarget): string {
  const target = AGENT_MCP_TARGETS[agent];
  return `# Integrate Arguslog (error tracking + MCP) into this project

You are integrating **Arguslog** — open-source multi-tenant error tracking — into the user's project. Do all of the following in one pass, then report a summary.

**Target agent**: ${target.name}.

**Environment notes**:
- This workspace may or may not be a git repository. Do **not** assume \`git\` works; if \`.git/\` is missing, list edited files with plain \`ls\` / \`find\` instead of \`git status\`.
- The DSN and PAT at the bottom of this document are real, freshly issued, and already inlined into every snippet. Do **not** ask the user to "replace placeholders" — there shouldn't be any. If you see a literal \`<GENERATE_DSN_FIRST>\` or \`<GENERATE_PAT_FIRST>\` string, stop and tell the user to revisit the Connect page so the dashboard can re-issue credentials.`;
}

function agentDetectionInstructions(): string {
  return `## Step 1 — detect the stack

Read these files at the repo root (whichever exist) and decide which SDK applies. Match the FIRST rule that fits; bail to "node" only if a backend service has no framework signal.

- \`package.json\` — inspect \`dependencies\` + \`devDependencies\`.
- \`pyproject.toml\` / \`requirements.txt\` / \`setup.py\` — Python project.
- \`build.gradle\` / \`build.gradle.kts\` / \`pom.xml\` — JVM project (Spring Boot if you see spring-boot-starter).

If the repo is a monorepo (workspaces / pnpm-workspace.yaml / turbo.json), apply the detection per workspace and pick the right SDK for each.`;
}

function agentSdkInstallTable(): string {
  const rows = SDK_CATALOG.map(
    (p) =>
      `| \`${p.slug}\` | ${p.detect} | \`${p.installCmd}\` | \`${p.pkg}@${p.version}\` | ${p.entryFile} |`,
  ).join('\n');

  // Per-platform init + wrap templates. Each block is what the agent should paste verbatim
  // once it picks the slug matching its stack-detection. `<DSN>` stays literal here — the
  // credentials block at the end of the prompt carries the real value the agent substitutes.
  //
  // Multi-file SDKs (currently only Vue) ship `initFiles[]` — env vars + an installer module
  // + the mount file. We render each as its own labelled fenced block so the agent writes the
  // exact file path. The credentials block instructs DSN substitution in the env file rather
  // than in app code, matching the production-realistic env-driven shape we recommend.
  const templates = SDK_CATALOG.map((p) => {
    const wrap = p.wrapSnippet
      ? `\n\nThen wire the framework wrap / boundary as well:\n\n\`\`\`${p.lang}\n${p.wrapSnippet}\n\`\`\``
      : '';
    const initBlock =
      'initFiles' in p && p.initFiles
        ? p.initFiles
            .map((f) => `**\`${f.path}\`**:\n\n\`\`\`${f.lang ?? p.lang}\n${f.contents}\n\`\`\``)
            .join('\n\n')
        : `\`\`\`${p.lang}\n${p.initSnippet}\n\`\`\``;
    return `#### \`${p.slug}\` — full template

${initBlock}${wrap}`;
  }).join('\n\n');

  return `## Step 2 — install the SDK and wire init()

Pick the row matching your detection. Use the EXACT package + version listed below — the catalog is the single source of truth.

| Slug | Detection signal | Install command | Pinned package | Where to inject init() |
|---|---|---|---|---|
${rows}

### Init templates per stack

For the detected slug, paste the matching block verbatim. The templates already carry the recommended default integrations (\`globalHandlers\`, \`autoBreadcrumbs\` for browser/UI SDKs; \`processHandlers\`/\`http\` for server SDKs) and, where the SDK exports one, the framework wrap (React/Vue/Next.js/React-Native \`<ArguslogErrorBoundary>\`, Angular \`provideArguslog\` providers, Next.js server \`instrumentation.ts\`). Replace \`<DSN>\` with the real DSN from the Credentials block at the bottom — no other edits needed.

${templates}`;
}

function agentMcpInstructions(
  agent: AgentTarget,
  pat: string,
  isSelfHosted: boolean,
  apiUrl: string,
): string {
  const target = AGENT_MCP_TARGETS[agent];
  const envBlock = isSelfHosted ? `,\n        "ARGUSLOG_API_URL": "${apiUrl}"` : '';
  const httpUrl = isSelfHosted
    ? '<self-hosted MCP URL — run `npx @arguslog/mcp-server@^2.3.1` locally and point this at it>'
    : 'https://mcp.arguslog.org/mcp';

  if (agent === 'claude-code') {
    return `## Step 3 — register the Arguslog MCP server

**Preferred (Claude Code CLI)** — run from the project root:

\`\`\`bash
claude mcp add arguslog ${httpUrl} \\
  --transport http \\
  --header "Authorization: Bearer ${pat}"
\`\`\`

**Fallback (file-based)** — write to \`${target.configPath}\`:

\`\`\`json
{
  "mcpServers": {
    "arguslog": {
      "type": "http",
      "url": "${httpUrl}",
      "headers": {
        "Authorization": "Bearer ${pat}"${envBlock}
      }
    }
  }
}
\`\`\`

> ${target.note}`;
  }

  if (agent === 'cursor') {
    return `## Step 3 — register the Arguslog MCP server

Write \`${target.configPath}\` (create the file if missing):

\`\`\`json
{
  "mcpServers": {
    "arguslog": {
      "url": "${httpUrl}",
      "headers": {
        "Authorization": "Bearer ${pat}"${envBlock}
      }
    }
  }
}
\`\`\`

Restart Cursor (or click "Reload MCP" in Settings → MCP) so the server is picked up.

> ${target.note}`;
  }

  if (agent === 'codex') {
    const apiUrlHeader = isSelfHosted ? `, "X-Arguslog-API-URL" = "${apiUrl}"` : '';
    return `## Step 3 — register the Arguslog MCP server

Codex stores MCP configuration in **TOML**, not JSON. Append the following block to \`${target.configPath}\` (create the file and the \`.codex/\` directory if missing — Codex auto-creates them on first run):

\`\`\`toml
[mcp_servers.arguslog]
url = "${httpUrl}"
http_headers = { "Authorization" = "Bearer ${pat}"${apiUrlHeader} }
enabled = true
startup_timeout_sec = 10
tool_timeout_sec = 60
\`\`\`

Restart Codex (or run \`codex mcp list\`) so the new server is registered.

> ${target.note}`;
  }

  if (agent === 'copilot') {
    return `## Step 3 — register the Arguslog MCP server

GitHub Copilot has two surfaces that each read a different MCP config file. Write **both** so the install works whether the user is in VS Code (Copilot Chat extension) or running the \`gh copilot\` CLI:

**A. Copilot Chat (VS Code)** — write \`.vscode/mcp.json\`:

\`\`\`json
{
  "servers": {
    "arguslog": {
      "type": "http",
      "url": "${httpUrl}",
      "headers": {
        "Authorization": "Bearer ${pat}"${envBlock}
      }
    }
  }
}
\`\`\`

**B. Copilot CLI** — write the workspace config at \`.mcp.json\` (or for a user-wide install, \`~/.copilot/mcp-config.json\`). Copilot CLI migrated to this layout per https://gh.io/copilotcli-mcpmigrate:

\`\`\`json
{
  "mcpServers": {
    "arguslog": {
      "type": "http",
      "url": "${httpUrl}",
      "headers": {
        "Authorization": "Bearer ${pat}"${envBlock}
      }
    }
  }
}
\`\`\`

Reload the VS Code window (Chat) and/or re-run \`gh copilot\` (CLI) so the new server is picked up.

> ${target.note}`;
  }

  if (agent === 'windsurf') {
    return `## Step 3 — register the Arguslog MCP server

Write \`${target.configPath}\` (create the file and parent directories if missing). **Windsurf uses \`serverUrl\` (not \`url\`)** for HTTP-transport MCP servers:

\`\`\`json
{
  "mcpServers": {
    "arguslog": {
      "serverUrl": "${httpUrl}",
      "headers": {
        "Authorization": "Bearer ${pat}"${envBlock}
      }
    }
  }
}
\`\`\`

Reload Windsurf (or click "Refresh" in the MCP plugin panel) so the new server is registered.

> ${target.note}`;
  }

  // continue — YAML in .continue/mcpServers/<name>.yaml (1.0+ schema)
  const apiUrlHeader = isSelfHosted ? `\n    X-Arguslog-API-URL: "${apiUrl}"` : '';
  return `## Step 3 — register the Arguslog MCP server

Continue 1.0+ reads each MCP server from its own YAML file under \`.continue/mcpServers/\` in the workspace root (the legacy \`experimental.modelContextProtocolServers\` array in \`~/.continue/config.json\` is deprecated).

Write \`.continue/mcpServers/arguslog.yaml\` (create the directory if missing):

\`\`\`yaml
name: arguslog
type: streamable-http
url: ${httpUrl}
requestOptions:
  headers:
    Authorization: "Bearer ${pat}"${apiUrlHeader}
\`\`\`

Reload Continue (Command Palette → "Continue: Reload providers") so the new server is picked up.

> ${target.note}`;
}

function agentVerifyStep(): string {
  return `## Step 4 — verify

1. Run the project's normal build/test command (\`npm run build\`, \`pnpm test\`, \`pytest -q\`, \`./gradlew build\` — pick what fits, skip if no obvious command exists).
2. Call the MCP server: invoke the \`list_projects\` Arguslog tool. A successful response means the PAT and MCP wiring work end-to-end. If MCP isn't available in this session yet, note that the user needs to reload their editor / agent for the new config to take effect — this is expected, not a failure.
3. Trigger a synthetic error (throw + catch in JS, \`raise\` in Python) and confirm the event reaches the dashboard at https://app.arguslog.org. If you can't run the project from here, leave this as a quick verification step for the user.

## Step 5 — report

Summarise:
- Which SDK you installed and where you wired init().
- Which MCP config file you touched.
- Any genuine TODOs (e.g., environment variables that still need to be set in CI). Do **not** list "replace the PAT" or "replace the DSN" as a TODO — both are already inlined above.
- List the files you changed. If \`.git/\` exists in the repo root, \`git status --short\` is the most concise way; otherwise just enumerate the paths you edited. Don't fail the report if \`git status\` errors — fall back to the plain file list.`;
}

function agentCredentialsBlock(dsn: string, pat: string): string {
  return `## Credentials (auto-provisioned by Arguslog)

- **DSN** (for the SDK): \`${dsn}\` — already inlined in every SDK snippet above.
- **Personal Access Token** (for the MCP server): \`${pat}\` — already inlined in every MCP config snippet above.

These are real, freshly issued by the Arguslog dashboard, and substituted at the exact key the agent reads (\`headers.Authorization\` for HTTP transports; \`env.ARGUSLOG_PAT\` for stdio). You do **not** need to ask the user to replace them — there shouldn't be any placeholders left.

### Revoking or rotating later

If the user wants different credentials at any point:

- **Via dashboard**: revoke the PAT at https://app.arguslog.org/me/tokens and the DSN on the project's Keys page. Then revisit the Connect page; a "Rotate" button mints new ones and refills every snippet.
- **Via MCP** (after this install is done and the server is registered): the agent itself can call \`delete_me_tokens\` / \`revoke_dsn\` to invalidate the current pair, then \`create_me_tokens\` / \`create_dsn\` to mint replacements, and write the new values back into the config files touched above. End-to-end rotation without leaving the editor.`;
}

export function buildAgentPrompt(ctx: SnippetContext, agent: AgentTarget): string {
  const dsn = v(ctx.dsn, DSN_PLACEHOLDER);
  const pat = v(ctx.pat, PAT_PLACEHOLDER);
  const isSelfHosted = ctx.apiUrl !== 'https://arguslog.org';
  return [
    agentRoleHeader(agent),
    agentDetectionInstructions(),
    agentSdkInstallTable(),
    agentMcpInstructions(agent, pat, isSelfHosted, ctx.apiUrl),
    agentVerifyStep(),
    agentCredentialsBlock(dsn, pat),
  ].join('\n\n');
}

export function buildSnippets(ctx: SnippetContext): ConnectSnippet[] {
  const dsn = v(ctx.dsn, DSN_PLACEHOLDER);
  const pat = v(ctx.pat, PAT_PLACEHOLDER);
  const apiUrl = ctx.apiUrl;
  const isSelfHosted = apiUrl !== 'https://arguslog.org';

  return [
    // ─── Agent group ──────────────────────────────────────────────────────────
    // Magic-prompt entries. Each is a self-contained markdown brief the user pastes into the
    // matching coding agent; the agent detects the stack, installs the SDK, wires init(), and
    // registers the Arguslog MCP server — single paste, ~3 seconds of user effort.
    {
      id: 'agent-claude-code',
      group: 'agent',
      client: 'Claude Code',
      language: 'markdown',
      description:
        'Paste into Claude Code. It will detect the stack, install the SDK, wire init(), and register the MCP server via `claude mcp add`.',
      code: buildAgentPrompt(ctx, 'claude-code'),
    },
    {
      id: 'agent-cursor',
      group: 'agent',
      client: 'Cursor',
      language: 'markdown',
      description:
        'Paste into Cursor (Composer / chat). It will install the SDK and write the MCP entry to .cursor/mcp.json.',
      code: buildAgentPrompt(ctx, 'cursor'),
    },
    {
      id: 'agent-codex',
      group: 'agent',
      client: 'Codex',
      language: 'markdown',
      description:
        'Paste into Codex CLI. It will install the SDK and write the MCP entry to .mcp.json (same shape as Claude Code).',
      code: buildAgentPrompt(ctx, 'codex'),
    },
    {
      id: 'agent-copilot',
      group: 'agent',
      client: 'GitHub Copilot',
      language: 'markdown',
      description:
        'Paste into GitHub Copilot (VS Code Chat or gh CLI). The prompt writes BOTH .vscode/mcp.json (Chat) and .mcp.json (CLI, post-migration).',
      code: buildAgentPrompt(ctx, 'copilot'),
    },
    {
      id: 'agent-windsurf',
      group: 'agent',
      client: 'Windsurf',
      language: 'markdown',
      description:
        'Paste into Windsurf chat. It will install the SDK and write ~/.codeium/windsurf/mcp_config.json.',
      code: buildAgentPrompt(ctx, 'windsurf'),
    },
    {
      id: 'agent-continue',
      group: 'agent',
      client: 'Continue',
      language: 'markdown',
      description:
        'Paste into Continue chat (VS Code / JetBrains). It will install the SDK and drop a .continue/mcpServers/arguslog.yaml in the workspace.',
      code: buildAgentPrompt(ctx, 'continue'),
    },

    // ─── Workflow group ──────────────────────────────────────────────────────
    // "Read · Eval · Triage · Loop" canned workflows. Mirror of the catalog the MCP server
    // exposes via prompts/list — agents that support MCP prompts discover these natively;
    // for everyone else, the Connect tab is the copy-paste path. Bodies use literal
    // placeholders (<PROJECT_ID>, <VERSION>, <ISSUE_ID>) — the user fills them in once when
    // pasting. Unlike the install prompts, these don't need DSN/PAT inlining: the MCP server
    // already authenticates per-request with the Bearer header.
    ...WORKFLOWS.map((w) => ({
      id: w.id,
      group: 'workflow' as const,
      client: w.client,
      language: 'markdown' as const,
      description: w.description,
      code: w.body,
    })),

    // ─── SDK group ────────────────────────────────────────────────────────────
    {
      id: 'sdk-javascript',
      group: 'sdk',
      client: 'JavaScript / Browser',
      language: 'js',
      description: 'Vanilla browser bundle. Drop this near app boot, before any other code.',
      code: `import { init } from '@arguslog/sdk-browser';

init({
  dsn: '${dsn}',
  environment: 'production',
  integrations: ['globalHandlers', 'autoBreadcrumbs'],
});`,
    },
    {
      // React's Connect tab is the same workflow-first 7-step flow as Vue: ConnectProjectPage
      // renders <OnboardingFlow slug="react" /> when this sub-tab is active. The `code`
      // field here remains a faithful single-paste version of the 3-file install for the
      // copy-button + any consumer that reads ConnectSnippet.code directly.
      id: 'sdk-react',
      group: 'sdk',
      client: 'React',
      language: 'tsx',
      description:
        'React 18 / 19 + Vite. Env-driven installer with a no-op fallback for local dev. Walk the 7 steps below — install plus one instrumented workflow gives you trustworthy onboarding.',
      code: (() => {
        const reactEntry = SDK_CATALOG.find((p) => p.slug === 'react');
        if (!reactEntry || !('initFiles' in reactEntry) || !reactEntry.initFiles) return '';
        return reactEntry.initFiles
          .map((f) => `// === ${f.path} ===\n${f.contents.replace(/<DSN>/g, dsn)}`)
          .join('\n\n');
      })(),
    },
    {
      // Angular ships the environment-driven provider flow — ConnectProjectPage renders
      // <OnboardingFlow slug="angular" /> when this tab is active.
      id: 'sdk-angular',
      group: 'sdk',
      client: 'Angular',
      language: 'ts',
      description:
        'Angular 17+ standalone. Environment-driven provider with empty-DSN no-op for local dev. Walk the 6 steps below — install plus one instrumented workflow gives you trustworthy onboarding.',
      code: (() => {
        const angularEntry = SDK_CATALOG.find((p) => p.slug === 'angular');
        if (!angularEntry || !('initFiles' in angularEntry) || !angularEntry.initFiles) return '';
        return angularEntry.initFiles
          .map((f) => `// === ${f.path} ===\n${f.contents.replace(/<DSN>/g, dsn)}`)
          .join('\n\n');
      })(),
    },
    {
      // Vue is special-cased in the UI: ConnectProjectPage renders <VueOnboardingFlow /> when
      // this sub-tab is active, walking the operator through the env-driven installer + a
      // post-install verification checklist + a workflow-first telemetry example. The
      // `code` field here is the same multi-file content the agent prompt sees — it stays as
      // a fallback for any consumer that renders ConnectSnippet.code directly (and for the
      // copy-button which still gets full text). For the UI flow itself the component reads
      // from SDK_CATALOG directly so it can label each file separately.
      id: 'sdk-vue',
      group: 'sdk',
      client: 'Vue',
      language: 'ts',
      description:
        'Vue 3 + Vite. Env-driven installer with a no-op fallback for local dev. Walk the 7 steps below — the install plus one instrumented workflow gives you trustworthy onboarding.',
      code: (() => {
        const vueEntry = SDK_CATALOG.find((p) => p.slug === 'vue');
        if (!vueEntry || !('initFiles' in vueEntry) || !vueEntry.initFiles) return '';
        return vueEntry.initFiles
          .map((f) => `// === ${f.path} ===\n${f.contents.replace(/<DSN>/g, dsn)}`)
          .join('\n\n');
      })(),
    },
    {
      // Next.js dual-path workflow-first flow: ConnectProjectPage special-cases this slug
      // and renders <OnboardingFlow slug="nextjs" />. The `code` here is the concatenated
      // 4-file dual-path install for the legacy copy-button consumers.
      id: 'sdk-nextjs',
      group: 'sdk',
      client: 'Next.js',
      language: 'ts',
      description:
        'Next.js 13+. Env-driven dual-path install (server `instrumentation.ts` + client `app/layout.tsx`) with a no-op fallback for local dev. Walk the 8 steps below — the install plus one instrumented workflow gives you trustworthy onboarding across both runtimes.',
      code: (() => {
        const nextEntry = SDK_CATALOG.find((p) => p.slug === 'nextjs');
        if (!nextEntry || !('initFiles' in nextEntry) || !nextEntry.initFiles) return '';
        return nextEntry.initFiles
          .map((f) => `// === ${f.path} ===\n${f.contents.replace(/<DSN>/g, dsn)}`)
          .join('\n\n');
      })(),
    },
    {
      // React Native ships the Expo env-driven workflow-first flow — ConnectProjectPage
      // renders <OnboardingFlow slug="react-native" /> when this tab is active.
      id: 'sdk-react-native',
      group: 'sdk',
      client: 'React Native',
      language: 'tsx',
      description:
        'React Native + Expo (bare RN supported via react-native-config). Env-driven installer with a no-op fallback for local dev. Walk the 8 steps below — install plus one instrumented workflow plus device verification gives you trustworthy onboarding.',
      code: (() => {
        const rnEntry = SDK_CATALOG.find((p) => p.slug === 'react-native');
        if (!rnEntry || !('initFiles' in rnEntry) || !rnEntry.initFiles) return '';
        return rnEntry.initFiles
          .map((f) => `// === ${f.path} ===\n${f.contents.replace(/<DSN>/g, dsn)}`)
          .join('\n\n');
      })(),
    },
    {
      id: 'sdk-node',
      group: 'sdk',
      client: 'Node.js',
      language: 'ts',
      description:
        'Server-side capture. Express / Fastify / plain Node — global handlers included.',
      code: `import { init, captureException } from '@arguslog/sdk-node';

init({
  dsn: '${dsn}',
  environment: process.env.NODE_ENV,
  release: process.env.GIT_SHA,
});

process.on('unhandledRejection', (err) => captureException(err));`,
    },
    {
      id: 'sdk-python',
      group: 'sdk',
      client: 'Python',
      language: 'python',
      description:
        'Zero-dependency Python SDK. Works with Django, Flask, FastAPI, scripts, workers.',
      code: `import arguslog

arguslog.init(
    dsn="${dsn}",
    environment="production",
    install_excepthook=True,        # global sys.excepthook
    install_logging_handler=30,     # forward WARNING+
)`,
    },
    {
      id: 'sdk-java',
      group: 'sdk',
      client: 'Java / Spring Boot',
      language: 'java',
      description:
        'Maven coordinate: org.arguslog:arguslog-java-sdk. Configure in application.yml; auto-config picks it up.',
      code: `# application.yml
arguslog:
  dsn: ${dsn}
  environment: production
  release: \${GIT_SHA:dev}`,
    },

    // ─── MCP group ────────────────────────────────────────────────────────────
    {
      id: 'mcp-claude-desktop',
      group: 'mcp',
      client: 'Claude Desktop',
      language: 'json',
      description:
        'Edit ~/Library/Application Support/Claude/claude_desktop_config.json (macOS) or %APPDATA%/Claude/claude_desktop_config.json (Windows).',
      code: `{
  "mcpServers": {
    "arguslog": {
      "command": "npx",
      "args": ["-y", "@arguslog/mcp-server"],
      "env": {
        "ARGUSLOG_PAT": "${pat}"${isSelfHosted ? `,\n        "ARGUSLOG_API_URL": "${apiUrl}"` : ''}
      }
    }
  }
}`,
    },
    {
      id: 'mcp-cursor',
      group: 'mcp',
      client: 'Cursor',
      language: 'json',
      description: 'Cursor → Settings → MCP → Add new server. Paste this into the editor.',
      code: `{
  "mcpServers": {
    "arguslog": {
      "command": "npx",
      "args": ["-y", "@arguslog/mcp-server"],
      "env": {
        "ARGUSLOG_PAT": "${pat}"${isSelfHosted ? `,\n        "ARGUSLOG_API_URL": "${apiUrl}"` : ''}
      }
    }
  }
}`,
    },
    {
      id: 'mcp-claude-code',
      group: 'mcp',
      client: 'Claude Code',
      language: 'bash',
      description:
        'CLI add command. Re-running with the same name updates the existing entry instead of duplicating.',
      code: `claude mcp add arguslog \\
  --env ARGUSLOG_PAT='${pat}' ${isSelfHosted ? `\\\n  --env ARGUSLOG_API_URL='${apiUrl}' ` : ''}\\
  -- npx -y @arguslog/mcp-server`,
    },
    {
      id: 'mcp-continue',
      group: 'mcp',
      client: 'Continue',
      language: 'json',
      description:
        'Edit ~/.continue/config.json — add the entry under experimental.modelContextProtocolServers.',
      code: `{
  "experimental": {
    "modelContextProtocolServers": [
      {
        "name": "arguslog",
        "command": "npx",
        "args": ["-y", "@arguslog/mcp-server"],
        "env": {
          "ARGUSLOG_PAT": "${pat}"${isSelfHosted ? `,\n          "ARGUSLOG_API_URL": "${apiUrl}"` : ''}
        }
      }
    ]
  }
}`,
    },

    // ─── CLI group ────────────────────────────────────────────────────────────
    {
      id: 'cli',
      group: 'cli',
      client: 'Arguslog CLI',
      language: 'bash',
      description:
        'Install once, authenticate, then create releases and upload source maps from your CI pipeline.',
      code: `# Install (Node 18+)
npm install -g @arguslog/cli

# Authenticate (writes to ~/.arguslog/config.json)
export ARGUSLOG_PAT='${pat}'${isSelfHosted ? `\nexport ARGUSLOG_API_URL='${apiUrl}'` : ''}

# Verify and use
arguslog version
arguslog ping --project ${(() => dissectDsn(ctx.dsn).projectId)()}
arguslog releases new "v1.4.2" --project <projectId>
arguslog sourcemaps upload dist/assets/*.js.map \\
  --project <projectId> --release "v1.4.2" --path /assets`,
    },
    {
      id: 'curl-test',
      group: 'cli',
      client: 'curl (connectivity test)',
      language: 'bash',
      description:
        'Bare-curl POST to ingest using the project DSN. Useful when you want to verify the wire path without any SDK at all — e.g., from a CI smoke step.',
      code: (() => {
        const { ingestUrl, publicKey, projectId } = dissectDsn(ctx.dsn);
        const eventId = '0123456789abcdef0123456789abcdef';
        return `curl -i -X POST "${ingestUrl}/api/${projectId}/events" \\
  -H "X-Arguslog-Auth: Arguslog DSN ${publicKey}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "eventId":"${eventId}",
    "timestamp": '"$(date +%s%3N)"',
    "platform":"javascript",
    "sdk":{"name":"curl-probe","version":"1"},
    "level":"error",
    "exception":{"values":[{
      "type":"ArguslogConnectivityProbe",
      "value":"curl smoke from $(hostname)"
    }]},
    "tags":{"synthetic":"true","source":"curl"}
  }'

# Expected: HTTP 202 + JSON { "eventId": "..." }
# Issue appears on the dashboard within ~1s; search "synthetic=true" to find it.`;
      })(),
    },
  ];
}
