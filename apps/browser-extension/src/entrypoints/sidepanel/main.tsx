import { createRoot } from 'react-dom/client';

import '../../assets/tailwind.css';
import { AppProviders } from '../../app/providers/AppProviders';
import { SidepanelApp } from '../../app/routes/SidepanelApp';

const root = document.getElementById('root');
if (!root) {
  throw new Error('Sidepanel root element not found.');
}

createRoot(root).render(
  <AppProviders>
    <SidepanelApp />
  </AppProviders>,
);
