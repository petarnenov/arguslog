# @arguslog/sdk-react

[![npm version](https://img.shields.io/npm/v/@arguslog/sdk-react.svg)](https://www.npmjs.com/package/@arguslog/sdk-react)
[![license](https://img.shields.io/npm/l/@arguslog/sdk-react.svg)](https://github.com/petarnenov/arguslog/blob/main/LICENSE)

React 19 bindings for [Arguslog](https://arguslog.org) error tracking. Adds an
`<ArguslogErrorBoundary>` that captures render-time errors plus a `useArguslog()` hook for
imperative reporting from inside components.

Re-exports the entire [`@arguslog/sdk-browser`](https://www.npmjs.com/package/@arguslog/sdk-browser)
public API, so you don't need both packages in your imports.

Peer dependency: **React 18 or 19**. The error boundary is a plain class component and the
hook uses only `useMemo`, so the package works unchanged across both major versions.

## Install

```bash
pnpm add @arguslog/sdk-react
# or
npm install @arguslog/sdk-react
# or
yarn add @arguslog/sdk-react
```

`@arguslog/sdk-browser` is pulled in automatically as a regular dependency — no separate
install required.

## Quick start (env-driven, recommended)

For a Vite SPA, keep the DSN out of app code. Put it in `.env.local` and let a tiny
installer module mount Arguslog once at boot. The installer no-ops cleanly when the DSN is
missing — useful for local dev without keys.

```bash
# .env.local — DO NOT commit a real DSN
VITE_ARGUSLOG_DSN=arguslog://<publicKey>@<ingestHost>/api/<projectId>
VITE_APP_RELEASE=1.0.0
```

```ts
// src/arguslog.ts
import { init } from '@arguslog/sdk-react';

let installed = false;

export function installArguslog(): void {
  if (installed) return;
  const dsn = import.meta.env.VITE_ARGUSLOG_DSN;
  if (!dsn) return; // no-op when DSN is missing — safe for local dev

  init({
    dsn,
    environment: import.meta.env.MODE,
    release: import.meta.env.VITE_APP_RELEASE,
    integrations: ['globalHandlers', 'autoBreadcrumbs'],
  });
  installed = true;
}
```

```tsx
// src/main.tsx
import { createRoot } from 'react-dom/client';
import { ArguslogErrorBoundary } from '@arguslog/sdk-react';

import App from './App';
import { installArguslog } from './arguslog';

installArguslog();

createRoot(document.getElementById('root')!).render(
  <ArguslogErrorBoundary fallback={<div role="alert">Something went wrong.</div>}>
    <App />
  </ArguslogErrorBoundary>,
);
```

The boundary catches anything React throws during render / lifecycle / effect-cleanup, ships
it to Arguslog with `boundary: 'react'` tag, and renders the `fallback`. Imperative async
errors (event handlers, fetch callbacks) need explicit `captureException` because React
boundaries don't see those by design.

### Inline (single-file alternative)

If you must wire everything inline — e.g. a one-off demo or tutorial step — call `init()`
directly:

```tsx
import { init, ArguslogErrorBoundary } from '@arguslog/sdk-react';
import { createRoot } from 'react-dom/client';

init({
  dsn: 'arguslog://<publicKey>@<ingestHost>/api/<projectId>',
  environment: import.meta.env.MODE,
  integrations: ['globalHandlers', 'autoBreadcrumbs'],
});

createRoot(document.getElementById('root')!).render(
  <ArguslogErrorBoundary fallback={<div role="alert">Something went wrong.</div>}>
    <App />
  </ArguslogErrorBoundary>,
);
```

### Webpack / Create React App / Next.js Pages Router

`import.meta.env` shown above is a Vite/ESM feature. In Webpack-based toolchains (CRA, plain
Webpack, Next.js Pages Router) it is `undefined` at runtime. Use `process.env` instead and
inject the values at build time via `DefinePlugin` (or rely on the framework's built-in
`process.env.NEXT_PUBLIC_*` / `REACT_APP_*` conventions):

```ts
// webpack.config.js
new webpack.DefinePlugin({
  'process.env.ARGUSLOG_DSN': JSON.stringify(process.env.ARGUSLOG_DSN),
  'process.env.ARGUSLOG_RELEASE': JSON.stringify(process.env.ARGUSLOG_RELEASE),
});
```

```tsx
init({
  dsn: process.env.ARGUSLOG_DSN!,
  environment: process.env.NODE_ENV,
  release: process.env.ARGUSLOG_RELEASE,
  integrations: ['globalHandlers', 'autoBreadcrumbs'],
});
```

For CRA, prefix env vars with `REACT_APP_` (`REACT_APP_ARGUSLOG_DSN`); for Next.js Pages
Router, use `NEXT_PUBLIC_ARGUSLOG_DSN`.

### Behavior when `init()` has not been called

`captureException`, `captureMessage`, `addBreadcrumb`, and the scope setters all **no-op
silently** if no client has been initialised — they never throw. This means you can guard
SDK setup behind an env check without wrapping every call site:

```ts
if (process.env.ARGUSLOG_DSN) {
  init({ dsn: process.env.ARGUSLOG_DSN, … });
}

// Anywhere later — safe even if init was skipped:
captureException(err);
```

If you need to branch on whether the SDK is live (e.g. to render a "reporting enabled" badge
in dev tooling), call `useArguslog().isInitialized()`.

## API

Everything the browser SDK exports is re-exported from this package — see the
[browser SDK README](https://www.npmjs.com/package/@arguslog/sdk-browser) for `init`,
`captureException`, `captureMessage`, `setUser`, `setTag`, `setContext`, `addBreadcrumb`,
`flush`, and the `ArguslogOptions` shape.

### `<ArguslogErrorBoundary>`

```tsx
<ArguslogErrorBoundary fallback={…} onError={(error, info) => …}>
  <App />
</ArguslogErrorBoundary>
```

| Prop       | Type                                           | Notes                                                                  |
| ---------- | ---------------------------------------------- | ---------------------------------------------------------------------- |
| `fallback` | `ReactNode \| ({ error, reset }) => ReactNode` | Render-prop form gets a `reset()` to retry without a hard navigation.  |
| `onError`  | `(error: Error, info: ErrorInfo) => void`      | Side-channel — fires AFTER the SDK reports. Useful for custom logging. |
| `children` | `ReactNode`                                    | Subtree to protect.                                                    |

### `useArguslog()`

Stable bag of imperative helpers — same identity across renders, no `useEffect` needed.

```tsx
import { useArguslog } from '@arguslog/sdk-react';

function CheckoutButton() {
  const arguslog = useArguslog();

  return (
    <button
      onClick={async () => {
        try {
          await pay();
        } catch (err) {
          arguslog.captureException(err, { tags: { feature: 'checkout' } });
        }
      }}
    >
      Pay
    </button>
  );
}
```

The returned object exposes:

- `captureException(error, hint?)`
- `captureMessage(message, level?)`
- `addBreadcrumb(crumb)`
- `setUser(user | undefined)`
- `setTag(key, value)`
- `setContext(name, ctx)`
- `isInitialized()` — boolean check before calling other methods (handy in SSR).

## Patterns

### Reset after a recoverable error

```tsx
<ArguslogErrorBoundary
  fallback={({ error, reset }) => (
    <div role="alert">
      <p>{error.message}</p>
      <button onClick={reset}>Try again</button>
    </div>
  )}
>
  <App />
</ArguslogErrorBoundary>
```

### Per-route boundaries

Wrap inside React Router routes so a render error in one screen doesn't blank the entire
shell:

```tsx
<Route
  path="/orders/:id"
  element={
    <ArguslogErrorBoundary fallback={<OrderErrorFallback />}>
      <OrderDetail />
    </ArguslogErrorBoundary>
  }
/>
```

### Tag the user on login

```tsx
const { setUser } = useArguslog();
useEffect(() => {
  if (auth.user) setUser({ id: auth.user.id, email: auth.user.email });
  return () => setUser(undefined);
}, [auth.user]);
```

## SSR / Next.js

`init` reads `window` only when global handlers integration is enabled. Importing the
package in a server component or `getServerSideProps` is safe. For server-side capture you
typically want a separate Node SDK — coming in a future release.

## License

MIT — see [LICENSE](https://github.com/petarnenov/arguslog/blob/main/LICENSE).
