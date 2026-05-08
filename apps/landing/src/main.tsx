import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { LandingPage } from './pages/LandingPage';
import { Providers } from './providers';

const root = document.getElementById('root');
if (!root) throw new Error('#root not found');

createRoot(root).render(
  <StrictMode>
    <Providers>
      <LandingPage />
    </Providers>
  </StrictMode>,
);
