import { useArguslog } from '@arguslog/sdk-react';
import { useState } from 'react';

export function DemoBeforeSendPage() {
  const arguslog = useArguslog();
  const [log, setLog] = useState<string[]>([]);

  const fireSent = () => {
    const id = arguslog.captureMessage('event that beforeSend should pass through', 'info');
    setLog((prev) => [`pass-through → ${id ?? '(dropped)'}`, ...prev]);
  };

  const fireDropped = () => {
    arguslog.setTag('demo:drop', 'true');
    const id = arguslog.captureMessage('event that beforeSend should drop', 'info');
    arguslog.setTag('demo:drop', 'false');
    setLog((prev) => [`drop-attempt → ${id ?? '(dropped by beforeSend)'}`, ...prev]);
  };

  return (
    <div>
      <h1>beforeSend filter</h1>
      <p>
        <code>beforeSend</code> is the last hook before the wire — it sees the fully-built event and
        can mutate it or drop it entirely by returning <code>null</code>. Use cases: opt-out user
        preferences, late-bound context, redacting fields the regex scrubber misses, and
        rate-limiting noisy events.
      </p>
      <p>
        The example's <code>beforeSend</code> (in <code>arguslog.ts</code>) drops every event tagged{' '}
        <code>demo:drop=true</code>. The second button below sets that tag, fires the event, then
        clears the tag — so it's a one-shot drop.
      </p>
      <div className="row">
        <button type="button" onClick={fireSent}>
          Fire event (pass-through)
        </button>
        <button type="button" onClick={fireDropped}>
          Fire event (will be dropped)
        </button>
      </div>
      {log.length > 0 ? (
        <ul className="muted">
          {log.map((line, i) => (
            <li key={i}>
              <code>{line}</code>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
