import { flush } from '@arguslog/sdk-react';
import { useArguslog } from '@arguslog/sdk-react';
import { useState } from 'react';

export function DemoFlushPage() {
  const arguslog = useArguslog();
  const [status, setStatus] = useState<string>('idle');

  const fireAndFlush = async () => {
    arguslog.captureMessage('event right before flush', 'info');
    arguslog.captureMessage('and one more', 'info');
    setStatus('flushing…');
    const t0 = performance.now();
    await flush();
    const elapsed = Math.round(performance.now() - t0);
    setStatus(`flushed in ${elapsed}ms`);
  };

  return (
    <div>
      <h1>flush()</h1>
      <p>
        Returns a promise that resolves once the in-memory queue has been drained to the network.
        Real-world use: at <code>beforeunload</code> / page navigation in critical-event apps so the
        last events aren't lost when the tab closes. The SDK already does its best to push on
        visibility change, but an explicit flush is the belt-and-braces option.
      </p>
      <div className="row">
        <button type="button" onClick={fireAndFlush}>
          Capture 2 events + flush
        </button>
      </div>
      <p className="muted">
        Status: <code>{status}</code>
      </p>
    </div>
  );
}
