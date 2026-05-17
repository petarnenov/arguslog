import { createRoot } from 'react-dom/client';

import '../../assets/tailwind.css';
import { SettingsScreen } from '../../app/features/settings/SettingsScreen';
import { AppProviders } from '../../app/providers/AppProviders';

const root = document.getElementById('root');
if (!root) {
  throw new Error('Options root element not found.');
}

createRoot(root).render(
  <div className="mx-auto max-w-5xl p-6">
    <AppProviders>
      <SettingsScreen />
    </AppProviders>
  </div>,
);
