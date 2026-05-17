# Arguslog MCP Console extension

Chromium-first MV3 browser extension built with WXT. It connects to `https://mcp.arguslog.org/mcp`, stores the Arguslog PAT encrypted in the background service worker, and exposes curated operator surfaces for workspace, issues, releases, workflows, playbooks, and advanced MCP tool execution.

## Scripts

- `pnpm dev` — WXT development mode
- `pnpm build` — production build into `.output/chrome-mv3`
- `pnpm test` — unit tests
- `pnpm typecheck` — strict TypeScript validation

## Load in Chrome

1. Run `pnpm build`
2. Open `chrome://extensions`
3. Enable **Developer mode**
4. Click **Load unpacked**
5. Select `.output/chrome-mv3`

## Important paths

- `src/entrypoints/background.ts` — stateless MCP broker, auth, diagnostics, typed message handling
- `src/entrypoints/sidepanel/*` — primary React workspace UI
- `src/entrypoints/popup/*` — quick status surface
- `src/entrypoints/options/*` — settings and diagnostics export
- `src/entrypoints/arguslog.content.ts` — Argus dashboard page-context detection
- `src/shared/domain/*` — UI-facing services over the background message bus
