import { createRoot } from 'react-dom/client';

import '../../assets/tailwind.css';
import { PopupApp } from '../../app/features/popup/PopupApp';
import { AppProviders } from '../../app/providers/AppProviders';

const root = document.getElementById('root');
if (!root) {
  throw new Error('Popup root element not found.');
}

createRoot(root).render(
  <AppProviders>
    <PopupApp />
  </AppProviders>,
);
