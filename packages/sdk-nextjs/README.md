# @arguslog/sdk-nextjs

Arguslog SDK for Next.js 13.4+ (App Router and Pages Router).

Wraps `@arguslog/sdk-react` for the client and `@arguslog/sdk-node` for
the server, plus helpers for App Router route handlers, server actions,
Pages Router API routes, and the `instrumentation.ts` `onRequestError`
hook introduced in Next.js 15.

## Install

```bash
pnpm add @arguslog/sdk-nextjs
```

## Server: instrumentation.ts (recommended)

Create `instrumentation.ts` at the repo root (or under `src/` if your app
uses `src/`):

```ts
import type { Instrumentation } from 'next';

export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const { init } = await import('@arguslog/sdk-nextjs/server');
    init({
      dsn: process.env.ARGUSLOG_DSN!,
      release: process.env.ARGUSLOG_RELEASE,
      environment: process.env.NODE_ENV,
      integrations: ['processHandlers', 'http'],
    });
  }
}

export const onRequestError: Instrumentation.onRequestError = async (err, request, context) => {
  const { onRequestError } = await import('@arguslog/sdk-nextjs/server');
  return onRequestError(err, request, context);
};
```

`onRequestError` runs for every uncaught error in App Router pages,
layouts, route handlers, and server actions — no manual try/catch
needed in app code.

## Client: error boundary

In your root layout (App Router):

```tsx
'use client';

import { ArguslogErrorBoundary, init } from '@arguslog/sdk-nextjs/client';
import { useEffect } from 'react';

export default function RootLayout({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    init({
      dsn: process.env.NEXT_PUBLIC_ARGUSLOG_DSN!,
      integrations: ['globalHandlers'],
    });
  }, []);

  return (
    <html>
      <body>
        <ArguslogErrorBoundary fallback={<p>Something broke.</p>}>{children}</ArguslogErrorBoundary>
      </body>
    </html>
  );
}
```

## Manual wrappers (when you need fine-grained control)

```ts
import {
  wrapApiHandler, // Pages Router /pages/api/*
  wrapRouteHandler, // App Router /app/api/*/route.ts
  wrapServerAction, // App Router 'use server' fn
} from '@arguslog/sdk-nextjs/server';
```

Each wrapper passes through return values on success and re-throws after
calling `captureException`, so Next.js's own error UI still renders.

```ts
// app/api/checkout/route.ts
import { wrapRouteHandler } from '@arguslog/sdk-nextjs/server';

export const POST = wrapRouteHandler(async (req) => {
  const body = await req.json();
  // …
  return Response.json({ ok: true });
});
```

## Edge runtime

Edge routes run in a V8 isolate without `async_hooks`, so the Node
adapter (which uses `AsyncLocalStorage`) cannot run there. For now,
wrap edge handlers with manual try/catch and call `captureException`
from `@arguslog/sdk-browser`. A dedicated edge entry will follow.
