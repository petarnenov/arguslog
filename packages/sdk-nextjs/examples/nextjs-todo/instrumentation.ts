export async function register() {
  if (process.env.NEXT_RUNTIME !== 'nodejs') return;
  const dsn = process.env.ARGUSLOG_DSN;
  if (!dsn) return;

  const { init } = await import('@arguslog/sdk-nextjs/server');
  init({
    dsn,
    environment: process.env.NODE_ENV,
    release: process.env.NEXT_PUBLIC_APP_RELEASE,
    integrations: ['processHandlers', 'http'],
  });
}

export { onRequestError } from '@arguslog/sdk-nextjs/server';
