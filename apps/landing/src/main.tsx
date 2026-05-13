import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { LandingPage } from './pages/LandingPage';
import { StatusPage } from './pages/StatusPage';
import { Providers } from './providers';

const root = document.getElementById('root');
if (!root) throw new Error('#root not found');

// Two-page SPA — react-router would be overkill for this. Caddy's try_files already serves
// /index.html for any deep link; the JS below picks the right page from the actual pathname.
function App() {
  const path = window.location.pathname;
  if (path === '/status' || path.startsWith('/status/')) {
    return <StatusPage />;
  }
  return <LandingPage />;
}

createRoot(root).render(
  <StrictMode>
    <Providers>
      <App />
    </Providers>
  </StrictMode>,
);
