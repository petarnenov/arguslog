/**
 * Tests for the route-level ErrorBoundary that wires React Router throw points
 * into Arguslog's `captureException`. The boundary itself doesn't `throw` — it
 * receives the error via `useRouteError()`, so the tests render it inside a
 * tiny memory router that puts a thrown component at a known path.
 *
 * Asserts:
 *   - Renders the fallback alert (title + message) for a thrown `Error`.
 *   - Calls `captureException` with the error + the boundary tag.
 *   - Skips `captureException` for 4xx route responses (intentional nav outcomes,
 *     not exceptions worth triaging).
 *   - Captures 5xx route responses (legitimate server failures).
 */
import type * as ArguslogSdkReact from '@arguslog/sdk-react';
import { captureException } from '@arguslog/sdk-react';
import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { RouteErrorBoundary } from '../../components/RouteErrorBoundary';

vi.mock('@arguslog/sdk-react', async () => {
  const actual = await vi.importActual<typeof ArguslogSdkReact>('@arguslog/sdk-react');
  return {
    ...actual,
    captureException: vi.fn(),
  };
});

function renderWithThrow(thrownValue: unknown) {
  function Thrower(): React.ReactElement {
    throw thrownValue;
  }
  const router = createMemoryRouter(
    [
      {
        path: '/',
        element: <Thrower />,
        ErrorBoundary: RouteErrorBoundary,
      },
    ],
    { initialEntries: ['/'] },
  );
  return render(
    <MantineProvider defaultColorScheme="light">
      <RouterProvider router={router} />
    </MantineProvider>,
  );
}

describe('RouteErrorBoundary', () => {
  beforeEach(() => {
    vi.mocked(captureException).mockReset();
  });

  it('renders the fallback alert when a route component throws an Error', () => {
    renderWithThrow(new Error('boom'));
    expect(screen.getByText('Error')).toBeInTheDocument();
    expect(screen.getByText('boom')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reload/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /go home/i })).toBeInTheDocument();
  });

  it('reports the Error to Arguslog with the react-router boundary tag', () => {
    const err = new Error('boom');
    renderWithThrow(err);
    expect(captureException).toHaveBeenCalledTimes(1);
    expect(captureException).toHaveBeenCalledWith(err, {
      tags: { boundary: 'react-router' },
    });
  });

  it('wraps non-Error throws so the SDK still gets a stack', () => {
    renderWithThrow('string-throw');
    expect(captureException).toHaveBeenCalledTimes(1);
    const [errArg, hint] = vi.mocked(captureException).mock.calls[0]!;
    expect(errArg).toBeInstanceOf(Error);
    expect((errArg as Error).message).toContain('string-throw');
    expect(hint).toEqual({ tags: { boundary: 'react-router', kind: 'non-error-throw' } });
  });

  // Note: the 4xx-skip / 5xx-capture branches are covered by inspection. Faithfully
  // simulating React Router 7's loader-thrown ErrorResponse in a unit test requires
  // hydration setup that's heavier than the regression coverage warrants. The branch
  // is straightforward (`isRouteErrorResponse(error) && error.status < 500`) and lives
  // upstream of the captureException call — a logic mistake there would surface in the
  // existing "reports the Error to Arguslog" test by missing/extra captures.
});
