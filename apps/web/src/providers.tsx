import { ArgusErrorBoundary, init as initArgus } from '@argus/sdk-react';
import { MantineProvider, createTheme } from '@mantine/core';
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
      initArgus({
        dsn: env.VITE_DOGFOOD_DSN,
        environment: import.meta.env.MODE,
        release: env.VITE_RELEASE,
        integrations: ['globalHandlers', 'breadcrumbs'],
      });
    }
  }, []);

  return (
    <MantineProvider theme={theme} defaultColorScheme="auto">
      <Notifications position="top-right" />
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ArgusErrorBoundary fallback={<div role="alert">Something went wrong.</div>}>
            {children}
          </ArgusErrorBoundary>
        </AuthProvider>
      </QueryClientProvider>
    </MantineProvider>
  );
}
