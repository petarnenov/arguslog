# React TODO — Arguslog React SDK example

A minimal Vite + React 19 TODO app wired end-to-end to the [`@arguslog/sdk-react`](../../)
package. Every public surface of the SDK has at least one demo route so you can click it
from the browser and verify the event lands on your Arguslog dashboard.

## What this demo proves

The app intentionally exercises every public surface of the React SDK in a real-world shape:

| SDK feature                                 | Where it's wired                                                                                                   |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `init()` with full `ArguslogOptions`        | [`src/arguslog.ts`](src/arguslog.ts) — DSN, release, environment, sampling, scrubbing, beforeSend, integrations    |
| `globalHandlers` integration                | `arguslog.ts` — `window.onerror` + `onunhandledrejection` reported automatically                                   |
| `autoBreadcrumbs` integration               | `arguslog.ts` — console / fetch / xhr / history / DOM clicks / resourceErrors / webVitals / longTasks / visibility |
| Global `setTag` / `setContext`              | `arguslog.ts` — `component`, `framework`, `runtime` baked into every event                                         |
| `<ArguslogErrorBoundary>` (top-level)       | [`src/App.tsx`](src/App.tsx) — wraps the whole app, falls back to `ErrorFallback`                                  |
| `<ArguslogErrorBoundary>` (nested)          | `/demo/boundary` — independent boundary around a child that throws                                                 |
| `useArguslog()` hook                        | [`src/pages/HomePage.tsx`](src/pages/HomePage.tsx) + every demo page                                               |
| `captureException`                          | `/demo/capture-exception` + `HomePage` "Trigger handled error" button                                              |
| `captureMessage`                            | `/demo/capture-message`                                                                                            |
| All severity levels (`debug`→`fatal`)       | `/demo/levels`                                                                                                     |
| Unhandled sync error                        | `/demo/unhandled-sync` — `setTimeout(() => { throw … }, 0)`                                                        |
| Unhandled promise rejection                 | `/demo/unhandled-async` — `Promise.reject` + async/await without try/catch                                         |
| `setUser` / clear                           | `/demo/user`                                                                                                       |
| `setTag` (event-scoped)                     | `/demo/tags`                                                                                                       |
| `setContext`                                | `/demo/context`                                                                                                    |
| `addBreadcrumb` (manual)                    | `/demo/breadcrumbs` + every TODO action                                                                            |
| PII / secret scrubbing with `extraPatterns` | `/demo/scrubbing`                                                                                                  |
| `beforeSend` filter                         | `/demo/before-send`                                                                                                |
| `flush()` before unload                     | `/demo/flush`                                                                                                      |
| `getClient()` + `parseDsn()` introspection  | `/demo/client`                                                                                                     |

## Project layout

```
react-todo/
├── index.html                        — Vite entry HTML
├── package.json                      — React 19 + Vite + workspace ref to @arguslog/sdk-react
├── tsconfig.json
├── vite.config.ts
├── .env.example                      — VITE_ARGUSLOG_DSN / RELEASE / ENV
├── src/
│   ├── main.tsx                      — initArguslog() before React mounts
│   ├── App.tsx                       — router + top-level ArguslogErrorBoundary
│   ├── arguslog.ts                   — single source of SDK config (commented)
│   ├── styles.css
│   ├── components/
│   │   ├── DemoMenu.tsx              — sidebar listing every demo route
│   │   └── ErrorFallback.tsx         — boundary fallback UI
│   └── pages/
│       ├── HomePage.tsx              — TODO list with breadcrumbs per action
│       └── Demo*.tsx                 — one page per SDK capability
```

## Quick start

```bash
# 1. From this directory:
cp .env.example .env
# 2. Edit .env — paste the DSN from your Arguslog project's "Keys" page.

# 3. Install deps:
pnpm install      # or npm install / yarn install

# 4. Start the dev server:
pnpm dev          # → http://localhost:5180
```

Open the app, then open the Arguslog dashboard side-by-side. Click through the demo links in
the sidebar — each one emits one or more events you can watch arrive in real time.

## Verifying the wiring

After `pnpm dev` is up, try this minimum smoke sequence:

1. **Home / TODO** — add a todo. Open DevTools → Network and confirm a request to
   `<dsn-host>/api/<projectId>/events` for the breadcrumb-only ping (or wait for the next
   capture to flush them).
2. **`/demo/capture-exception`** — click the button. The event should appear on the dashboard
   within ~1s, tagged `feature: demo:capture-exception`.
3. **`/demo/boundary`** — click "Break child component". The local boundary catches the error,
   reports it tagged `boundary: react`, and shows the fallback. Click "Reset boundary" to
   recover.
4. **`/demo/unhandled-sync`** + **`/demo/unhandled-async`** — both should reach the dashboard
   via the `globalHandlers` integration with no try/catch in user code.

If events never arrive, check:

- DevTools → Network for a 4xx response from the ingest URL (usually a wrong DSN).
- Console output — with `debug: true` (the default in dev), the SDK logs send/queue activity.
- That you're on the right project on the dashboard.

## Building for production

```bash
pnpm build        # tsc + vite build → dist/
pnpm preview      # static-serve dist/ for a smoke test
```

A real production deploy should also:

- Set `VITE_ARGUSLOG_RELEASE` to a stable identifier (git SHA or package version) so events tie
  back to a release row in the dashboard.
- Upload source maps for that release via the Arguslog CLI:

  ```bash
  npx @arguslog/cli releases new --project <id> --version "$VITE_ARGUSLOG_RELEASE"
  npx @arguslog/cli sourcemaps upload dist/assets/*.js.map \
    --project <id> --release "$VITE_ARGUSLOG_RELEASE" \
    --path /assets
  ```

- Drop `sampleRate` (currently `1.0` for visibility) to something sustainable for your volume,
  e.g. `0.2`.
- Remove or guard `debug: true` so SDK-internal logs don't ship to production browsers.

## Notes

This example is part of the monorepo's pnpm workspace (`packages/sdk-react/examples/*` glob in
`pnpm-workspace.yaml`), so `@arguslog/sdk-react` resolves to the live source via
`workspace:*`. Public mirror builds rewrite that to the published version automatically — the
example ships as a working install regardless of where it's consumed from.
