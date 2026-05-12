import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './App.js';
import { initArguslog } from './arguslog.js';
import './styles.css';

// Init BEFORE React mounts so the client is ready when the first effect runs and so any
// synchronous import-time errors propagating through this file land on the dashboard.
initArguslog();

const root = document.getElementById('root');
if (!root) throw new Error('#root not found');

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
