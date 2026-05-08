# @arguslog/sdk-browser

[![npm version](https://img.shields.io/npm/v/@arguslog/sdk-browser.svg)](https://www.npmjs.com/package/@arguslog/sdk-browser)
[![license](https://img.shields.io/npm/l/@arguslog/sdk-browser.svg)](https://github.com/petarnenov/arguslog/blob/main/LICENSE)

Browser SDK for [Arguslog](https://arguslog.org) — a multi-tenant error tracking platform.
Captures unhandled exceptions, promise rejections, and manually-reported errors from any
JavaScript app, then ships them to the Arguslog ingest endpoint where they're fingerprinted,
stored, and surfaced on the dashboard.

Ships ESM only. Tested against modern browsers and TypeScript 5.x. No runtime dependencies.

## Install

```bash
pnpm add @arguslog/sdk-browser
# or
npm install @arguslog/sdk-browser
# or
yarn add @arguslog/sdk-browser
```

## Quick start

Initialize once at the top of your app — typically in `main.ts` or wherever your bootstrap
lives. The SDK is a no-op until `init` runs, so it's safe to import in modules that load
before bootstrap.

```ts
import { init } from '@arguslog/sdk-browser';

init({
  dsn: 'https://<publicKey>@ingest.arguslog.org/<projectId>',
  environment: 'production',
  release: '1.4.0',
  integrations: ['globalHandlers', 'breadcrumbs'],
});
```

After `init`, unhandled `window.onerror` and `unhandledrejection` events are captured
automatically (when the `globalHandlers` integration is enabled). Manual reporting is
always available via `captureException` / `captureMessage`.

## DSN format

```
https://<publicKey>@<ingestHost>/<projectId>
```

Get yours from your Arguslog project settings page. The `publicKey` is safe to embed in a
public bundle — it's a project-scoped token, not a secret. Ingest authenticates the request
against the project ID + key combo and rejects unknown pairs with HTTP 401.

## API

### `init(options): ArguslogClient`

Configures and starts the client. Re-initializing tears down the previous handlers cleanly,
so hot-reload during dev doesn't accumulate stale listeners.

| Option           | Type                                              | Default      | Notes                                                                 |
| ---------------- | ------------------------------------------------- | ------------ | --------------------------------------------------------------------- |
| `dsn`            | `string`                                          | _required_   | See "DSN format" above.                                               |
| `release`        | `string`                                          | _none_       | Free-form version tag — git sha, semver, etc. Stamped on every event. |
| `environment`    | `string`                                          | _none_       | E.g. `production`, `staging`, `dev`.                                  |
| `sampleRate`     | `number` 0–1                                      | `1.0`        | Fraction of events kept; useful for high-traffic apps.                |
| `maxBreadcrumbs` | `number`                                          | `50`         | Ring-buffer size.                                                     |
| `beforeSend`     | `(event) => event \| null \| Promise<...>`        | _identity_   | Last-mile mutation / drop hook.                                       |
| `scrubbing`      | `{ enabled?: boolean; extraPatterns?: RegExp[] }` | enabled      | PII redaction in messages and URLs.                                   |
| `transport`      | `{ fetch?: typeof fetch; maxRetries?: number }`   | global fetch | Inject a custom fetch (testing) or bump retry budget.                 |
| `integrations`   | `('globalHandlers' \| 'breadcrumbs')[]`           | _none_       | Opt in to auto-instrumentation.                                       |
| `debug`          | `boolean`                                         | `false`      | Logs every send to the console — never enable in production.          |

### `captureException(error, hint?): string | undefined`

Reports a thrown value. Returns the generated event id, or `undefined` if the SDK isn't
initialized. The `hint` lets you tag the event without mutating client-wide state.

```ts
import { captureException } from '@arguslog/sdk-browser';

try {
  riskyThing();
} catch (err) {
  captureException(err, { level: 'error', tags: { feature: 'checkout' } });
}
```

Non-Error values (strings, plain objects, symbols) are wrapped synthetically so you don't
have to coerce upstream.

### `captureMessage(message, level?): string | undefined`

Sends a string event without a stack trace. Useful for breadcrumb-level signals.

```ts
captureMessage('Cart abandoned at step 3', 'warning');
```

### `setUser(user | undefined)`

Attaches user identity to subsequent events. Pass `undefined` to clear (e.g. on logout).

```ts
setUser({ id: '42', email: 'jane@example.com' });
// later
setUser(undefined);
```

### `setTag(key, value)` / `setContext(name, ctx)`

`setTag` adds a single key/value to every event going forward; `setContext` attaches an
arbitrary object under a named bucket (think "request", "feature flags", etc.).

### `addBreadcrumb(crumb)`

Records a navigation / click / network / custom event. The last `maxBreadcrumbs` are attached
to every captured exception, so debugging gets the trail leading up to the error.

```ts
addBreadcrumb({
  category: 'navigation',
  message: 'User clicked Buy',
  level: 'info',
  data: { sku: 'A-123' },
});
```

### `flush(): Promise<void>`

Awaits the in-flight queue. Useful before `window.unload` or in service workers.

## Integrations

### `globalHandlers`

Hooks `window.onerror` and `window.onunhandledrejection`. Errors that bubble to the platform
without a try/catch land here. Enable in `init({ integrations: ['globalHandlers'] })`.

### `breadcrumbs`

Captures lightweight navigation, click, and console events automatically. Pairs well with
`globalHandlers` so the breadcrumbs are present when an unhandled error fires.

## Privacy / scrubbing

Built-in regex patterns redact common PII (emails, IPs, US-style SSNs, JWT-ish tokens) from
message strings and URLs before send. Add custom patterns via
`scrubbing.extraPatterns: [/\bUSR-\d+\b/]`. Disable entirely with `scrubbing.enabled: false`
when you control the input shape and want full fidelity.

## React?

Use [`@arguslog/sdk-react`](https://www.npmjs.com/package/@arguslog/sdk-react) — it
re-exports this SDK plus a `<ArguslogErrorBoundary>` and `useArguslog()` hook.

## License

MIT — see [LICENSE](https://github.com/petarnenov/arguslog/blob/main/LICENSE).
