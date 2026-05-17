import browser from 'webextension-polyfill';

import { handleBackgroundRequest } from '../background/messaging/router';

export default defineBackground({
  type: 'module',
  main() {
    browser.runtime.onMessage.addListener((message) => handleBackgroundRequest(message));
  },
});
