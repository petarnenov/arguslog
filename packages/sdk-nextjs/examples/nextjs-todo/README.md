# Next.js TODO — Arguslog Next.js SDK example

A minimal Next.js 15 + App Router TODO app wired to [`@arguslog/sdk-nextjs`](../../). Shows
the _standard_ integration surface a real app needs:

- `instrumentation.ts` — server-side `init()` + `onRequestError` for Next 15
- `app/arguslog.client.ts` + `app/layout.tsx` — client SDK init + top-level `ArguslogErrorBoundary`
- `app/api/todos/route.ts` — every method wrapped with `wrapRouteHandler`
- `app/page.tsx` — `useArguslog()` hook, manual breadcrumbs per action, demo buttons to verify capture

The TODO list is stored **in-memory on the server** and accessed through the wrapped API
route — that gives `wrapRouteHandler` a real reason to exist. The list resets when the dev
server restarts; that's intentional for a demo.

## What this demo proves

| SDK feature                           | Where it's wired                                                                          |
| ------------------------------------- | ----------------------------------------------------------------------------------------- |
| `init()` (server)                     | [`instrumentation.ts`](instrumentation.ts) — Node runtime only                            |
| `onRequestError` (Next 15 hook)       | [`instrumentation.ts`](instrumentation.ts) — re-exported from the SDK                     |
| `init()` (client)                     | [`app/arguslog.client.ts`](app/arguslog.client.ts)                                        |
| `<ArguslogErrorBoundary>`             | [`app/layout.tsx`](app/layout.tsx) — wraps the whole app                                  |
| `useArguslog()` + `addBreadcrumb`     | [`app/page.tsx`](app/page.tsx) — one crumb per todo action                                |
| `captureException`                    | `app/page.tsx` — "captureException()" demo button                                         |
| Error boundary catch                  | `app/page.tsx` — "Throw render error" demo button                                         |
| `wrapRouteHandler`                    | [`app/api/todos/route.ts`](app/api/todos/route.ts) — GET/POST/PATCH/DELETE                |
| Server-side capture via wrapped route | `app/page.tsx` "Trigger server error" → `GET /api/todos?fail=1` throws inside the wrapper |

## Quick start

```bash
# From this directory:
cp .env.example .env.local
# Edit .env.local — paste the DSN from your Arguslog project's "Keys" page.

# From the repo root (so pnpm picks up the workspace):
pnpm install
pnpm --filter @arguslog/example-nextjs-todo dev   # → http://localhost:3000
```

You can run this example without a DSN — both `installArguslog()` and `instrumentation.ts`
no-op when their respective env vars are unset, so the TODO app still works locally. You
just won't see events on a dashboard until you configure one.

## Verifying the wiring

After `pnpm dev` is up, with your DSN configured:

1. **Add a todo** — entry appears; DevTools → Network shows `POST /api/todos` 200. A
   breadcrumb (`category: todo, message: add`) attaches to subsequent events.
2. **Click "captureException()"** — event appears on the dashboard within ~1s, tagged
   `demo: capture-exception`.
3. **Click "Throw render error (boundary)"** — `HomePage` throws during render, the
   top-level `ArguslogErrorBoundary` catches it and shows the fallback. Event lands on
   the dashboard tagged with the boundary's metadata.
4. **Click "Trigger server error"** — `GET /api/todos?fail=1` throws inside
   `wrapRouteHandler`, which captures the exception before re-throwing it for Next's
   error-rendering chain. The event appears server-side, tagged with route metadata.

If events never arrive:

- Verify both `ARGUSLOG_DSN` and `NEXT_PUBLIC_ARGUSLOG_DSN` are set (server and client
  bundles each need their own).
- Restart the dev server after editing `.env.local` — Next caches env vars at boot.
- Confirm the dashboard is on the project the DSN points at.

## Building for production

```bash
pnpm --filter @arguslog/example-nextjs-todo build
pnpm --filter @arguslog/example-nextjs-todo start   # serves the production build
```

A real production deploy should additionally:

- Set `NEXT_PUBLIC_APP_RELEASE` to a stable identifier (git SHA or app version) so
  events tie back to a release row.
- Enable `productionBrowserSourceMaps: true` in `next.config.mjs` and upload sourcemaps
  with the Arguslog CLI for that release — see the
  [SDK README](../../README.md#sourcemap-upload) for the recipe.
- Drop server `init()`'s `integrations` if you don't need `http` auto-capture, to keep
  request overhead minimal.

## Notes

This example is part of the monorepo's pnpm workspace (`packages/sdk-nextjs/examples/*`
glob in `pnpm-workspace.yaml`), so `@arguslog/sdk-nextjs` resolves to the live source via
`workspace:*`. Public mirror builds rewrite that to the published version automatically —
the example installs cleanly regardless of where it's consumed from.
