import { init, setContext, setTag, type ArguslogOptions } from '@arguslog/sdk-react';

/**
 * Centralised SDK initialisation. Called once from {@link main.tsx} before React mounts so the
 * client is ready for the very first render — any earlier-than-React errors (synchronous module
 * evaluation, top-level await failures) still land on the dashboard via the global handlers.
 *
 * Every option is shown with a comment explaining when you'd use it. Real apps usually pick a
 * subset; this is a demo, so we wire everything.
 */
export function initArguslog(): void {
  const dsn = import.meta.env.VITE_ARGUSLOG_DSN;
  if (!dsn) {
    console.warn(
      '[arguslog] VITE_ARGUSLOG_DSN is empty — events will not be sent. Copy .env.example to .env and fill in your project DSN.',
    );
    return;
  }

  const options: ArguslogOptions = {
    dsn,
    release: import.meta.env.VITE_ARGUSLOG_RELEASE,
    environment: import.meta.env.VITE_ARGUSLOG_ENV ?? 'development',

    // Sample 100% in dev. Production apps usually drop to 0.1–0.3 to keep volume sane.
    sampleRate: 1.0,

    // Cap the breadcrumb trail per event. Older crumbs are dropped FIFO once exceeded.
    maxBreadcrumbs: 50,

    // Mask PII before send. The SDK ships defaults for common patterns (Authorization headers,
    // credit-card-shaped numbers); extraPatterns adds project-specific shapes.
    scrubbing: {
      enabled: true,
      extraPatterns: [
        /todo_secret_[a-z0-9]+/gi,
        /demo[_-]?api[_-]?key/gi,
      ],
    },

    // Final filter — last chance to mutate or drop. Returning null drops the event entirely.
    // Real apps use this for: rate-limited side-effects, opting out by user preference,
    // injecting late-bound context the SDK doesn't know about.
    beforeSend: (event) => {
      // Demo: drop events with the magic tag the /demo/before-send page sets.
      if (event.tags?.['demo:drop'] === 'true') {
        // eslint-disable-next-line no-console
        console.info('[arguslog] beforeSend dropped event because demo:drop=true');
        return null;
      }
      return event;
    },

    // Opt-in integrations. 'autoBreadcrumbs' is a meta-flag that turns on every breadcrumb
    // adapter (console, fetch, xhr, history, dom, resourceErrors, webVitals, longTasks,
    // visibility, workerErrors). 'globalHandlers' wires window.onerror + onunhandledrejection.
    integrations: ['autoBreadcrumbs', 'globalHandlers'],

    // debug=true logs SDK internals to the console — keep off in production.
    debug: import.meta.env.DEV,
  };

  init(options);

  // Global tags applied to every subsequent event. Per-event setTag still wins on key clash.
  setTag('component', 'react-todo');
  setTag('framework', 'react-19');

  // Global context shows up under "Contexts" on the event detail page — good for environment
  // metadata that doesn't fit a single tag value.
  setContext('runtime', {
    userAgent: navigator.userAgent,
    language: navigator.language,
    viewport: `${window.innerWidth}x${window.innerHeight}`,
  });
}
