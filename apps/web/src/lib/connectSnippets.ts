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
  },
  {
    slug: 'react',
    pkg: '@arguslog/sdk-react',
    version: '2.0.1',
    installCmd: 'npm install @arguslog/sdk-react@^2',
    detect: 'package.json contains "react"',
    entryFile: 'src/main.tsx (Vite) or src/index.tsx (CRA)',
  },
  {
    slug: 'angular',
    pkg: '@arguslog/sdk-angular',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-angular@^2',
    detect: 'package.json contains "@angular/core"',
    entryFile: 'src/app/app.config.ts (provideArguslog())',
  },
  {
    slug: 'vue',
    pkg: '@arguslog/sdk-vue',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-vue@^2',
    detect: 'package.json contains "vue" (>= 3.x)',
    entryFile: 'src/main.ts (app.use(arguslogPlugin))',
  },
  {
    slug: 'nextjs',
    pkg: '@arguslog/sdk-nextjs',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-nextjs@^2',
    detect: 'package.json contains "next"',
    entryFile: 'instrumentation.ts at repo root (Next 13+ instrumentation hook)',
  },
  {
    slug: 'web3',
    pkg: '@arguslog/sdk-web3',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-web3@^2',
    detect: 'package.json contains "viem", "ethers", or "@solana/web3.js"',
    entryFile: 'wherever you currently init() the wallet client',
  },
  {
    slug: 'react-native',
    pkg: '@arguslog/sdk-react-native',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-react-native@^2',
    detect: 'package.json contains "react-native"',
    entryFile: 'App.tsx (top of the file, above the root component)',
  },
  {
    slug: 'node',
    pkg: '@arguslog/sdk-node',
    version: '2.0.0',
    installCmd: 'npm install @arguslog/sdk-node@^2',
    detect: 'package.json with no frontend framework (Express, Fastify, plain Node, workers)',
    entryFile: 'the FIRST file your process loads (e.g., src/index.ts before any handler import)',
  },
  {
    slug: 'java-spring',
    pkg: 'org.arguslog:java-sdk',
    version: '2.0.0',
    installCmd:
      'add to build.gradle (implementation "org.arguslog:java-sdk:2.0.0") or pom.xml dependency block',
    detect: 'build.gradle / build.gradle.kts / pom.xml with Spring Boot starter',
    entryFile: 'src/main/resources/application.yml (arguslog.dsn property)',
  },
  {
    slug: 'python',
    pkg: 'arguslog',
    version: '2.0.0',
    installCmd: 'pip install "arguslog>=2,<3"  (or uv add arguslog>=2)',
    detect: 'pyproject.toml, requirements.txt, or setup.py',
    entryFile:
      'the application entry — Django wsgi.py / Flask app.py / FastAPI main.py / a worker boot script',
  },
] as const;

export type AgentTarget =
  | 'claude-code'
  | 'cursor'
  | 'codex'
  | 'copilot'
  | 'windsurf'
  | 'continue'
  | 'aider';

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
      configPath: '.mcp.json (project root) — same shape as Claude Code',
      note: 'Codex CLI reads the same .mcp.json file Claude Code uses, so a single config covers both.',
    },
    copilot: {
      name: 'GitHub Copilot',
      configPath: '.vscode/mcp.json (Copilot Chat in VS Code) AND .mcp.json (Copilot CLI)',
      note: 'GitHub Copilot CLI migrated from .vscode/mcp.json to .mcp.json (https://gh.io/copilotcli-mcpmigrate); the prompt writes both so the same install works for the VS Code extension and the gh CLI.',
    },
    windsurf: {
      name: 'Windsurf',
      configPath: '~/.codeium/windsurf/mcp_config.json',
      note: 'Windsurf (Codeium) keeps MCP config in its own Codeium folder — separate from Cursor.',
    },
    continue: {
      name: 'Continue',
      configPath: '~/.continue/config.json (under experimental.modelContextProtocolServers)',
      note: 'Continue 0.0.52+ supports Streamable HTTP; older versions fall back to stdio with `npx -y @arguslog/mcp-server`.',
    },
    aider: {
      name: 'Aider',
      configPath: '~/.aider.conf.yml (mcp-servers block)',
      note: 'Aider is stdio-only — it runs `npx -y @arguslog/mcp-server` locally and receives the PAT via env var.',
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
  return `## Step 2 — install the SDK and wire init()

Pick the row matching your detection. Use the EXACT package + version listed below — the catalog is the single source of truth.

| Slug | Detection signal | Install command | Pinned package | Where to inject init() |
|---|---|---|---|---|
${rows}

For \`init({ dsn })\`, use the DSN provided at the bottom of this document. JS/TS SDKs share the same option shape:

\`\`\`ts
import { init } from '@arguslog/sdk-<slug>';
init({ dsn: '<DSN>', environment: process.env.NODE_ENV });
\`\`\`

Python:

\`\`\`python
import arguslog
arguslog.init(dsn="<DSN>", environment="production", install_excepthook=True)
\`\`\`

Java / Spring (application.yml):

\`\`\`yaml
arguslog:
  dsn: "<DSN>"
  environment: production
\`\`\``;
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

**B. Copilot CLI** — write \`.mcp.json\` at the repo root (same shape Claude Code / Codex use; Copilot CLI migrated to this file per https://gh.io/copilotcli-mcpmigrate):

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

Reload the VS Code window (Chat) and/or re-run \`gh copilot\` (CLI) so the new server is picked up.

> ${target.note}`;
  }

  if (agent === 'windsurf') {
    return `## Step 3 — register the Arguslog MCP server

Write \`${target.configPath}\` (create the file and parent directories if missing):

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

Reload Windsurf so the new MCP server is registered.

> ${target.note}`;
  }

  if (agent === 'continue') {
    return `## Step 3 — register the Arguslog MCP server

Edit \`${target.configPath}\` and add an entry under \`experimental.modelContextProtocolServers\`:

\`\`\`json
{
  "experimental": {
    "modelContextProtocolServers": [
      {
        "name": "arguslog",
        "transport": "http",
        "url": "${httpUrl}",
        "headers": {
          "Authorization": "Bearer ${pat}"${envBlock}
        }
      }
    ]
  }
}
\`\`\`

If the user's Continue is older than 0.0.52 (no HTTP MCP transport), fall back to stdio:

\`\`\`json
{
  "experimental": {
    "modelContextProtocolServers": [
      {
        "name": "arguslog",
        "command": "npx",
        "args": ["-y", "@arguslog/mcp-server"],
        "env": {
          "ARGUSLOG_PAT": "${pat}"${
            isSelfHosted ? `,\n          "ARGUSLOG_API_URL": "${apiUrl}"` : ''
          }
        }
      }
    ]
  }
}
\`\`\`

Restart Continue so the new server is picked up.

> ${target.note}`;
  }

  // aider — stdio-only; PAT lives in env var, hosted HTTP URL is ignored
  return `## Step 3 — register the Arguslog MCP server

Aider does not support Streamable HTTP MCP servers yet, so we run \`@arguslog/mcp-server\` locally over stdio. Edit \`${target.configPath}\` (create the file if missing) and add:

\`\`\`yaml
mcp-servers:
  arguslog:
    command: npx
    args:
      - "-y"
      - "@arguslog/mcp-server"
    env:
      ARGUSLOG_PAT: "${pat}"${isSelfHosted ? `\n      ARGUSLOG_API_URL: "${apiUrl}"` : ''}
\`\`\`

The PAT travels through the \`ARGUSLOG_PAT\` env var into the locally-spawned mcp-server process — same real token, different transport. Restart Aider so the new server is registered.

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
        'Paste into Continue chat (VS Code / JetBrains). It will install the SDK and update ~/.continue/config.json.',
      code: buildAgentPrompt(ctx, 'continue'),
    },
    {
      id: 'agent-aider',
      group: 'agent',
      client: 'Aider',
      language: 'markdown',
      description:
        'Paste into the Aider CLI. Aider is stdio-only — instructions install @arguslog/mcp-server locally with the PAT in env.',
      code: buildAgentPrompt(ctx, 'aider'),
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
