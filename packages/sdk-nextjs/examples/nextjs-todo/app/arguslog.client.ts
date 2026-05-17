'use client';
import { init } from '@arguslog/sdk-nextjs/client';

let installed = false;

export function installArguslog(): void {
  if (installed) return;
  const dsn = process.env.NEXT_PUBLIC_ARGUSLOG_DSN;
  if (!dsn) return;

  init({
    dsn,
    environment: process.env.NODE_ENV,
    release: process.env.NEXT_PUBLIC_APP_RELEASE,
    integrations: ['globalHandlers', 'autoBreadcrumbs'],
  });
  installed = true;
}
