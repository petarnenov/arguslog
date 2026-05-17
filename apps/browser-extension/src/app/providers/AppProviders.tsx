import { ArguslogErrorBoundary, init } from '@arguslog/sdk-react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { PropsWithChildren } from 'react';

import { WHITE_SCREEN_TEST_ERROR_MESSAGE } from '../../shared/constants/diagnostics';

let arguslogInitialized = false;

function ensureArguslogInitialized() {
  if (arguslogInitialized) {
    return;
  }

  init({
    dsn: 'arguslog://6A5CC6H3AGGTUJRBXNRAVEKGTAGZPTMQ@ingest.arguslog.org/api/28',
    environment: import.meta.env.MODE,
    integrations: ['globalHandlers', 'autoBreadcrumbs'],
  });

  arguslogInitialized = true;
}

ensureArguslogInitialized();

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
});

function renderBoundaryFallback({ error }: { error: Error; reset: () => void }) {
  if (error.message === WHITE_SCREEN_TEST_ERROR_MESSAGE) {
    return <div className="min-h-screen bg-white" data-testid="white-screen-fallback" />;
  }

  return <p>Something went wrong.</p>;
}

export function AppProviders(props: PropsWithChildren) {
  return (
    <ArguslogErrorBoundary fallback={renderBoundaryFallback}>
      <QueryClientProvider client={queryClient}>{props.children}</QueryClientProvider>
    </ArguslogErrorBoundary>
  );
}
