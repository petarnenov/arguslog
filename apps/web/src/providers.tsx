import { ArguslogErrorBoundary, init as initArguslog } from '@arguslog/sdk-react';
import { MantineProvider, createTheme, localStorageColorSchemeManager } from '@mantine/core';
import '@mantine/core/styles.css';
import '@mantine/charts/styles.css';
import '@mantine/notifications/styles.css';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect, useMemo, type ReactNode } from 'react';

import { AuthProvider } from './auth/AuthProvider';
import { env } from './env';
import './i18n';

const theme = createTheme({
  primaryColor: 'green',
  fontFamily: 'Inter, system-ui, -apple-system, sans-serif',
  headings: { fontFamily: 'Inter, system-ui, sans-serif' },
});

// Shared key with apps/landing so a future shared-domain cookie bridge can promote the choice
// across origins. Today the two SPAs read independent localStorage entries.
const colorSchemeManager = localStorageColorSchemeManager({ key: 'arguslog-color-scheme' });

export function Providers({ children }: { children: ReactNode }) {
  const queryClient = useMemo(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { staleTime: 30_000, refetchOnWindowFocus: false, retry: 1 },
        },
      }),
    [],
  );

  useEffect(() => {
    if (env.VITE_DOGFOOD_DSN) {
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
      <Notifications position="top-right" />
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ArguslogErrorBoundary fallback={<div role="alert">Something went wrong.</div>}>
            {children}
          </ArguslogErrorBoundary>
        </AuthProvider>
      </QueryClientProvider>
    </MantineProvider>
  );
}
