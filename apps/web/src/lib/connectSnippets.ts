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
arguslog releases new "v1.4.2" --project <projectId>
arguslog sourcemaps upload dist/assets/*.js.map \\
  --project <projectId> --release "v1.4.2" --path /assets`,
    },
  ];
}
