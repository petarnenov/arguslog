export function DemoUnhandledSyncPage() {
  const fire = () => {
    // Thrown OUTSIDE React's render path → React's boundary doesn't catch it. The browser's
    // window.onerror fires; the SDK's globalHandlers integration is wired to that and reports
    // the error with the "globalHandlers" tag. No try/catch needed.
    setTimeout(() => {
      throw new Error('Demo: unhandled synchronous error from setTimeout');
    }, 0);
  };

  return (
    <div>
      <h1>Unhandled sync error</h1>
      <p>
        Throws from inside a <code>setTimeout</code> callback, which bypasses React's error
        boundary. The SDK's <code>globalHandlers</code> integration (enabled in
        <code> arguslog.ts</code>) hooks <code>window.onerror</code> and reports the error
        automatically.
      </p>
      <p className="muted">
        Open DevTools → Console to see the browser's "Uncaught Error" log alongside the SDK's
        report.
      </p>
      <button type="button" onClick={fire}>
        Throw uncaught error
      </button>
    </div>
  );
}
