import { init } from '@arguslog/sdk-react';
import { render, screen } from '@testing-library/react';
import { Component } from 'react';
import type { PropsWithChildren, ReactNode } from 'react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';

import { AppProviders } from '../../src/app/providers/AppProviders';
import { WHITE_SCREEN_TEST_ERROR_MESSAGE } from '../../src/shared/constants/diagnostics';

vi.mock('@arguslog/sdk-react', async () => {
  const React = await import('react');

  class MockArguslogErrorBoundary extends React.Component<
    PropsWithChildren<{
      fallback: ReactNode | ((args: { error: Error; reset: () => void }) => ReactNode);
    }>,
    { error: Error | null }
  > {
    override state = { error: null as Error | null };

    static getDerivedStateFromError(error: Error) {
      return { error };
    }

    reset = () => {
      this.setState({ error: null });
    };

    override render() {
      if (this.state.error) {
        const { fallback } = this.props;
        if (typeof fallback === 'function') {
          return fallback({ error: this.state.error, reset: this.reset });
        }

        return fallback;
      }

      return this.props.children;
    }
  }

  return {
    ArguslogErrorBoundary: MockArguslogErrorBoundary,
    init: vi.fn(),
  };
});

class ThrowOnRender extends Component<{ message: string }> {
 override render(): ReactNode {
   throw new Error(this.props.message);
   return null;
 }
}

describe('AppProviders', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('initializes the SDK with the safe extension integration set', () => {
    expect(init).toHaveBeenCalledWith(
      expect.objectContaining({
        integrations: ['globalHandlers', 'autoBreadcrumbs'],
      }),
    );
  });

  it('renders a white fallback for the dedicated white-screen test crash', () => {
    render(
      <AppProviders>
        <ThrowOnRender message={WHITE_SCREEN_TEST_ERROR_MESSAGE} />
      </AppProviders>,
    );

    expect(screen.getByTestId('white-screen-fallback')).toBeInTheDocument();
    expect(screen.queryByText('Something went wrong.')).not.toBeInTheDocument();
  });

  it('keeps the generic fallback for other crashes', () => {
    render(
      <AppProviders>
        <ThrowOnRender message="Unexpected failure" />
      </AppProviders>,
    );

    expect(screen.getByText('Something went wrong.')).toBeInTheDocument();
  });
});
