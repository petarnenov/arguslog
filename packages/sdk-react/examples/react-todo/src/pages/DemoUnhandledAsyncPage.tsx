export function DemoUnhandledAsyncPage() {
  const fire = () => {
    // No .catch() → window.onunhandledrejection fires. The SDK's globalHandlers integration is
    // wired to that event too, so the error reaches the dashboard automatically.
    void Promise.reject(new Error('Demo: unhandled promise rejection'));
  };

  const fireAsync = () => {
    void (async () => {
      // Same shape, but expressed via async/await.
      await Promise.resolve();
      throw new Error('Demo: async/await without a try/catch');
    })();
  };

  return (
    <div>
      <h1>Unhandled promise rejection</h1>
      <p>
        Two paths to the same outcome: a promise that rejects without a <code>.catch</code>, and an
        async function whose error escapes. Both trigger <code>window.onunhandledrejection</code>;
        the SDK's <code>globalHandlers</code> integration reports them.
      </p>
      <div className="row">
        <button type="button" onClick={fire}>
          Promise.reject
        </button>
        <button type="button" onClick={fireAsync}>
          async/await without try/catch
        </button>
      </div>
    </div>
  );
}
