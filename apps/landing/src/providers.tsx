import { ArguslogErrorBoundary, init as initArguslog } from '@arguslog/sdk-react';
import { MantineProvider, createTheme } from '@mantine/core';
import '@mantine/core/styles.css';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect, useMemo, type ReactNode } from 'react';

import { env } from './env';
import './i18n';

const theme = createTheme({
  primaryColor: 'green',
  fontFamily: 'Inter, system-ui, -apple-system, sans-serif',
  headings: { fontFamily: 'Inter, system-ui, sans-serif' },
});

export function Providers({ children }: { children: ReactNode }) {
  const queryClient = useMemo(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // Catalog endpoint is essentially static — refetch sparingly.
            staleTime: 5 * 60_000,
            refetchOnWindowFocus: false,
            retry: 1,
          },
        },
      }),
    [],
  );

  useEffect(() => {
    if (env.VITE_DOGFOOD_DSN) {
      // Dogfood — landing emits its own errors back into Arguslog, same wiring as every other
      // Arguslog service. Bubbles up tracking integrations: globalHandlers (uncaught + promise
      // rejection) + breadcrumbs (clicks, fetches, console).
      initArguslog({
        dsn: env.VITE_DOGFOOD_DSN,
        environment: import.meta.env.MODE,
        release: env.VITE_RELEASE,
        integrations: ['globalHandlers', 'breadcrumbs'],
      });
    }
  }, []);

  return (
    <MantineProvider theme={theme} defaultColorScheme="auto">
      <QueryClientProvider client={queryClient}>
        <ArguslogErrorBoundary fallback={<div role="alert">Something went wrong.</div>}>
          {children}
        </ArguslogErrorBoundary>
      </QueryClientProvider>
    </MantineProvider>
  );
}
