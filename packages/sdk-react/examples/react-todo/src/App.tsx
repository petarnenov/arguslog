import { ArguslogErrorBoundary } from '@arguslog/sdk-react';
import { BrowserRouter, Route, Routes } from 'react-router';

import { DemoMenu } from './components/DemoMenu.js';
import { ErrorFallback } from './components/ErrorFallback.js';
import { DemoBeforeSendPage } from './pages/DemoBeforeSendPage.js';
import { DemoBoundaryPage } from './pages/DemoBoundaryPage.js';
import { DemoBreadcrumbsPage } from './pages/DemoBreadcrumbsPage.js';
import { DemoCaptureExceptionPage } from './pages/DemoCaptureExceptionPage.js';
import { DemoCaptureMessagePage } from './pages/DemoCaptureMessagePage.js';
import { DemoClientPage } from './pages/DemoClientPage.js';
import { DemoContextPage } from './pages/DemoContextPage.js';
import { DemoFlushPage } from './pages/DemoFlushPage.js';
import { DemoLevelsPage } from './pages/DemoLevelsPage.js';
import { DemoScrubbingPage } from './pages/DemoScrubbingPage.js';
import { DemoTagsPage } from './pages/DemoTagsPage.js';
import { DemoUnhandledAsyncPage } from './pages/DemoUnhandledAsyncPage.js';
import { DemoUnhandledSyncPage } from './pages/DemoUnhandledSyncPage.js';
import { DemoUserPage } from './pages/DemoUserPage.js';
import { HomePage } from './pages/HomePage.js';

export function App() {
  return (
    <BrowserRouter>
      <ArguslogErrorBoundary fallback={({ error, reset }) => <ErrorFallback error={error} reset={reset} />}>
        <div className="layout">
          <DemoMenu />
          <main className="content">
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/demo/capture-exception" element={<DemoCaptureExceptionPage />} />
              <Route path="/demo/capture-message" element={<DemoCaptureMessagePage />} />
              <Route path="/demo/user" element={<DemoUserPage />} />
              <Route path="/demo/tags" element={<DemoTagsPage />} />
              <Route path="/demo/context" element={<DemoContextPage />} />
              <Route path="/demo/breadcrumbs" element={<DemoBreadcrumbsPage />} />
              <Route path="/demo/unhandled-sync" element={<DemoUnhandledSyncPage />} />
              <Route path="/demo/unhandled-async" element={<DemoUnhandledAsyncPage />} />
              <Route path="/demo/scrubbing" element={<DemoScrubbingPage />} />
              <Route path="/demo/before-send" element={<DemoBeforeSendPage />} />
              <Route path="/demo/flush" element={<DemoFlushPage />} />
              <Route path="/demo/client" element={<DemoClientPage />} />
              <Route path="/demo/levels" element={<DemoLevelsPage />} />
              <Route path="/demo/boundary" element={<DemoBoundaryPage />} />
            </Routes>
          </main>
        </div>
      </ArguslogErrorBoundary>
    </BrowserRouter>
  );
}
