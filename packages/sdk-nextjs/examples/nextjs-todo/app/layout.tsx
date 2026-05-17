import type { ReactNode } from 'react';

import { ClientShell } from './client-shell';
import './globals.css';

export const metadata = {
  title: 'Arguslog Next.js TODO',
  description: 'Minimal Next.js 15 App Router example wired to @arguslog/sdk-nextjs.',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <ClientShell>{children}</ClientShell>
      </body>
    </html>
  );
}
