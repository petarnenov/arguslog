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

export type SnippetGroup = 'agent' | 'sdk' | 'mcp' | 'cli';

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
    version: '2.0.0',
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
    version: '2.0.1',
    installCmd: 'npm install @arguslog/sdk-react@^2',
    detect: 'package.json contains "react"',
    entryFile: 'src/main.tsx (Vite) or src/index.tsx (CRA)',
    lang: 'tsx',
    initSnippet: `import { init, ArguslogErrorBoundary } from '@arguslog/sdk-react';
import { createRoot } from 'react-dom/client';

init({
  dsn: '<DSN>',
  environment: process.env.NODE_ENV,
  integrations: ['globalHandlers', 'autoBreadcrumbs'],
});

createRoot(document.getElementById('root')!).render(
  <ArguslogErrorBoundary fallback={<p>Something went wrong.</p>}>
    <App />
  </ArguslogErrorBoundary>,
);`,
    wrapSnippet: null,
  },
  {
    slug: 'angular',
    pkg: '@arguslog/sdk-angular',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-angular@^2',
    detect: 'package.json contains "@angular/core"',
    entryFile: 'src/app/app.config.ts (provideArguslog())',
    lang: 'ts',
    initSnippet: `import { ApplicationConfig } from '@angular/core';
import { provideArguslog } from '@arguslog/sdk-angular';

export const appConfig: ApplicationConfig = {
  providers: [
    provideArguslog({
      dsn: '<DSN>',
      environment: 'production',
      integrations: ['globalHandlers', 'autoBreadcrumbs'],
    }),
    // ...your other providers
  ],
};`,
    wrapSnippet: null,
  },
  {
    slug: 'vue',
    pkg: '@arguslog/sdk-vue',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-vue@^2',
    detect: 'package.json contains "vue" (>= 3.x)',
    entryFile: 'src/main.ts (app.use(arguslogPlugin))',
    lang: 'ts',
    initSnippet: `import { createApp } from 'vue';
import { arguslogPlugin } from '@arguslog/sdk-vue';
import App from './App.vue';

const app = createApp(App);
app.use(arguslogPlugin, {
  dsn: '<DSN>',
  environment: 'production',
  integrations: ['globalHandlers', 'autoBreadcrumbs'],
});
app.mount('#app');`,
    wrapSnippet: `<!-- In your root template (e.g. App.vue), wrap routed content: -->
<template>
  <ArguslogErrorBoundary>
    <RouterView />
  </ArguslogErrorBoundary>
</template>

<script setup lang="ts">
import { ArguslogErrorBoundary } from '@arguslog/sdk-vue';
</script>`,
  },
  {
    slug: 'nextjs',
    pkg: '@arguslog/sdk-nextjs',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-nextjs@^2',
    detect: 'package.json contains "next"',
    entryFile: 'instrumentation.ts at repo root (Next 13+ instrumentation hook)',
    lang: 'ts',
    initSnippet: `// instrumentation.ts (repo root, or src/instrumentation.ts when using src/)
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const { init } = await import('@arguslog/sdk-nextjs/server');
    init({
      dsn: '<DSN>',
      environment: process.env.NODE_ENV,
      integrations: ['processHandlers', 'http'],
    });
  }
}

export { onRequestError } from '@arguslog/sdk-nextjs/server';`,
    wrapSnippet: `// app/layout.tsx — wrap your root layout with the client boundary AND init the client SDK.
'use client';
import { init, ArguslogErrorBoundary } from '@arguslog/sdk-nextjs/client';

init({
  dsn: '<DSN>',
  environment: process.env.NODE_ENV,
  integrations: ['globalHandlers', 'autoBreadcrumbs'],
});

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
    entryFile: 'App.tsx (top of the file, above the root component)',
    lang: 'tsx',
    initSnippet: `import { init, ArguslogErrorBoundary } from '@arguslog/sdk-react-native';

init({
  dsn: '<DSN>',
  environment: __DEV__ ? 'development' : 'production',
  integrations: ['globalHandlers'],
});

export default function App() {
  return (
    <ArguslogErrorBoundary fallback={<CrashScreen />}>
      <RootNavigator />
    </ArguslogErrorBoundary>
  );
}`,
    wrapSnippet: null,
  },
  {
    slug: 'node',
    pkg: '@arguslog/sdk-node',
    version: '2.0.0',
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
    version: '2.0.0',
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

export type AgentTarget =
  | 'claude-code'
  | 'cursor'
  | 'codex'
  | 'copilot'
  | 'windsurf'
  | 'continue';

/** Per-agent MCP config file location + install hint shown in the magic prompt. */
const AGENT_MCP_TARGETS: Record<AgentTarget, { name: string; configPath: string; note: string }> =
  {
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
  const templates = SDK_CATALOG.map((p) => {
    const wrap = p.wrapSnippet
      ? `\n\nThen wire the framework wrap / boundary as well:\n\n\`\`\`${p.lang}\n${p.wrapSnippet}\n\`\`\``
      : '';
    return `#### \`${p.slug}\` — full template

\`\`\`${p.lang}
${p.initSnippet}
\`\`\`${wrap}`;
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
  const envBlock = isSelfHosted
    ? `,\n        "ARGUSLOG_API_URL": "${apiUrl}"`
    : '';
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
      id: 'sdk-react',
      group: 'sdk',
      client: 'React',
      language: 'tsx',
      description:
        'React 18 / 19. Wrap your root in <ArguslogErrorBoundary>; use the useArguslog() hook for imperative reporting.',
      code: `import { init, ArguslogErrorBoundary } from '@arguslog/sdk-react';
import { createRoot } from 'react-dom/client';

init({
  dsn: '${dsn}',
  environment: process.env.NODE_ENV,
  integrations: ['globalHandlers', 'autoBreadcrumbs'],
});

createRoot(document.getElementById('root')!).render(
  <ArguslogErrorBoundary fallback={<p>Something went wrong.</p>}>
    <App />
  </ArguslogErrorBoundary>,
);`,
    },
    {
      id: 'sdk-node',
      group: 'sdk',
      client: 'Node.js',
      language: 'ts',
      description: 'Server-side capture. Express / Fastify / plain Node — global handlers included.',
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
      description: 'Zero-dependency Python SDK. Works with Django, Flask, FastAPI, scripts, workers.',
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
      description: 'Edit ~/.continue/config.json — add the entry under experimental.modelContextProtocolServers.',
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
