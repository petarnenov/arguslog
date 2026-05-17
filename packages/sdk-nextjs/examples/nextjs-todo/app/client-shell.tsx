'use client';

import { ArguslogErrorBoundary } from '@arguslog/sdk-nextjs/client';
import type { ReactNode } from 'react';

import { installArguslog } from './arguslog.client';

installArguslog();

export function ClientShell({ children }: { children: ReactNode }) {
  return (
    <ArguslogErrorBoundary
      fallback={
        <main className="fallback">
          <h1>Something went wrong</h1>
          <p>The error was reported to Arguslog. Refresh the page to try again.</p>
        </main>
      }
    >
      {children}
    </ArguslogErrorBoundary>
  );
}
