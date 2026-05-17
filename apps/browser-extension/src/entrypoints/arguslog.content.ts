import browser from 'webextension-polyfill';

import { parseArgusContext } from '../shared/utils/parse-page-context';

export default defineContentScript({
  matches: ['https://*.arguslog.org/*'],
  runAt: 'document_idle',
  main() {
    const context = parseArgusContext(new URL(window.location.href));
    if (!context) {
      return;
    }

    browser.runtime.sendMessage({
      type: 'page-context/publish',
      payload: context,
    });
  },
});
