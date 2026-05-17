import browser from 'webextension-polyfill';

function parseArgusContext(url: URL) {
  const match = /^\/org\/([^/]+)\/project\/(\d+)(?:\/issues\/(\d+))?/.exec(url.pathname);
  if (!match) {
    return undefined;
  }

  return {
    orgSlug: match[1],
    projectId: Number(match[2]),
    issueId: match[3] ? Number(match[3]) : undefined,
    sourceTabUrl: url.toString(),
    capturedAt: new Date().toISOString(),
  };
}

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
