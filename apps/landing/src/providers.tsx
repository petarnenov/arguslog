import { ArguslogErrorBoundary, init as initArguslog } from '@arguslog/sdk-react';
import { MantineProvider, createTheme, localStorageColorSchemeManager } from '@mantine/core';
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

// Shared key with apps/web so a future shared-domain cookie bridge can promote the choice
// across origins. Today the two SPAs read independent localStorage entries because they live
// on different origins (arguslog.org vs app.arguslog.org).
const colorSchemeManager = localStorageColorSchemeManager({ key: 'arguslog-color-scheme' });

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
    <MantineProvider
      theme={theme}
      defaultColorScheme="auto"
      colorSchemeManager={colorSchemeManager}
    >
      <QueryClientProvider client={queryClient}>
        <ArguslogErrorBoundary fallback={<div role="alert">Something went wrong.</div>}>
          {children}
        </ArguslogErrorBoundary>
      </QueryClientProvider>
    </MantineProvider>
  );
}
