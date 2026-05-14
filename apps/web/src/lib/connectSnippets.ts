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

export type SnippetGroup = 'sdk' | 'mcp' | 'cli';

export interface ConnectSnippet {
  /** Stable id — used for tab keys, test selectors, copy-button telemetry. */
  id: string;
  group: SnippetGroup;
  /** Display label for the tab. */
  client: string;
  /** Mantine `<Prism>` / `<Code>` language hint for syntax highlighting. */
  language: 'tsx' | 'ts' | 'js' | 'python' | 'java' | 'json' | 'bash';
  /** One-line context shown above the code block. */
  description: string;
  /** The literal code to paste. */
  code: string;
}

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

export function buildSnippets(ctx: SnippetContext): ConnectSnippet[] {
  const dsn = v(ctx.dsn, DSN_PLACEHOLDER);
  const pat = v(ctx.pat, PAT_PLACEHOLDER);
  const apiUrl = ctx.apiUrl;
  const isSelfHosted = apiUrl !== 'https://arguslog.org';

  return [
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
