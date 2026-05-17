import browser from 'webextension-polyfill';

import { runLifecycleEvent } from '../background/lifecycle';
import { handleBackgroundRequest } from '../background/messaging/router';

export default defineBackground({
  type: 'module',
  main() {
    // MV3 service workers are torn down on idle, and any listener registered inside
    // an async callback won't be re-armed on the next wake. Both top-level listeners
    // below stay valid across worker restarts — see Chrome's "to-service-workers"
    // migration guide for the rationale.
    browser.runtime.onMessage.addListener((message) => handleBackgroundRequest(message));
    browser.runtime.onInstalled.addListener((details) =>
      runLifecycleEvent({
        reason: details.reason,
        previousVersion: details.previousVersion,
        currentVersion: browser.runtime.getManifest().version,
      }),
    );
  },
});
