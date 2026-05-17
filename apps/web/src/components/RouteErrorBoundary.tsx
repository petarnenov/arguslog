/**
 * Per-route ErrorBoundary that closes the React-Router-catches-everything gap so
 * Arguslog actually observes its own dashboard's crashes.
 *
 * Without this component, every error thrown inside a route component is caught
 * by React Router at the route boundary, swallowed, and rendered as React Router's
 * default dev-only "💿 Hey developer 👋" UI. The outer `<ArguslogErrorBoundary>`
 * in `providers.tsx` never sees the error → `captureException` never fires →
 * we're the only error-tracking platform that can't observe its own dashboard.
 *
 * Wired into `router.tsx` as the `ErrorBoundary` on the auth-protected layout
 * route (covering every in-app page) plus the public top-level routes.
 *
 * Implementation notes:
 *   - We call `captureException` directly here rather than relying on the SDK's
 *     `<ArguslogErrorBoundary>` wrapper, because `<ArguslogErrorBoundary>` is a
 *     React error boundary (componentDidCatch) and React Router has already
 *     caught the error before it can bubble — so the SDK boundary never gets a
 *     chance to fire. We're doing the SDK boundary's job manually here.
 *   - The fallback UI is a Mantine `Alert` with the message + a reload button.
 *     Intentionally minimal — visual polish is a separate concern.
 *   - useEffect ensures `captureException` runs after render, not during, so
 *     React Router doesn't re-throw or double-render on the side-effect.
 */
import { captureException } from '@arguslog/sdk-react';
import { Alert, Button, Container, Group, Stack, Text, Title } from '@mantine/core';
import { useEffect } from 'react';
import { useRouteError, isRouteErrorResponse } from 'react-router';

function describeError(err: unknown): { title: string; message: string; stack?: string } {
  if (isRouteErrorResponse(err)) {
    return {
      title: `${err.status} ${err.statusText}`,
      message: typeof err.data === 'string' ? err.data : 'Request failed.',
    };
  }
  if (err instanceof Error) {
    return {
      title: err.name || 'Unexpected error',
      message: err.message,
      stack: err.stack,
    };
  }
  return {
    title: 'Unexpected error',
    message: String(err),
  };
}

export function RouteErrorBoundary() {
  const error = useRouteError();

  useEffect(() => {
    // Skip 4xx route responses (intentional "not found" / "unauthorized" navigation
    // outcomes) — those aren't exception-class events the operator needs to triage.
    // Real thrown Errors and 5xx responses always go through.
    if (isRouteErrorResponse(error) && error.status < 500) return;

    if (error instanceof Error) {
      captureException(error, {
        tags: { boundary: 'react-router' },
      });
    } else if (error !== undefined) {
      // Non-Error throws (strings, numbers, plain objects). Wrap so the SDK can
      // still capture a stack from the boundary frame.
      captureException(new Error(`Non-Error route throw: ${String(error)}`), {
        tags: { boundary: 'react-router', kind: 'non-error-throw' },
      });
    }
  }, [error]);

  const described = describeError(error);

  return (
    <Container size="sm" py="xl">
      <Stack gap="md">
        <Alert color="red" variant="light" title={described.title}>
          <Stack gap="xs">
            <Text size="sm">{described.message}</Text>
            {described.stack ? (
              <details>
                <summary style={{ cursor: 'pointer', fontSize: 12 }}>Stack trace</summary>
                <pre
                  style={{
                    fontSize: 11,
                    overflow: 'auto',
                    background: 'var(--mantine-color-dark-7)',
                    padding: 8,
                    borderRadius: 4,
                    color: 'var(--mantine-color-dark-0)',
                  }}
                >
                  {described.stack}
                </pre>
              </details>
            ) : null}
          </Stack>
        </Alert>
        <Group justify="flex-end">
          <Button variant="light" onClick={() => window.location.reload()}>
            Reload
          </Button>
          <Button variant="filled" component="a" href="/">
            Go home
          </Button>
        </Group>
        <Title order={6} c="dimmed" ta="center">
          The error has been reported to Arguslog.
        </Title>
      </Stack>
    </Container>
  );
}
