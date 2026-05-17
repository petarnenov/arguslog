import { useArguslog } from '@arguslog/sdk-react';
import { useState } from 'react';

export function DemoContextPage() {
  const arguslog = useArguslog();
  const [name, setName] = useState('checkout');
  const [json, setJson] = useState(
    JSON.stringify({ cartId: 'c-42', items: 3, total: '$129.99' }, null, 2),
  );
  const [status, setStatus] = useState<string | null>(null);

  const apply = () => {
    try {
      const ctx = JSON.parse(json);
      arguslog.setContext(name, ctx);
      setStatus(`context "${name}" set with ${Object.keys(ctx).length} key(s)`);
    } catch {
      setStatus('Invalid JSON — fix the body and try again.');
    }
  };

  const fire = () => {
    arguslog.captureMessage(`event with context "${name}"`, 'info');
  };

  return (
    <div>
      <h1>setContext</h1>
      <p>
        Contexts are richer than tags — full JSON blobs surfaced in the event detail page under
        their own section. Use them for environment metadata that doesn't fit a single value: device
        specs, request payloads, feature flag snapshots.
      </p>
      <div className="form-grid">
        <label>
          <span>Context name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label>
          <span>JSON body</span>
          <textarea rows={8} value={json} onChange={(e) => setJson(e.target.value)} />
        </label>
      </div>
      <div className="row">
        <button type="button" onClick={apply}>
          setContext
        </button>
        <button type="button" onClick={fire}>
          Fire event
        </button>
      </div>
      {status ? <p className="muted">{status}</p> : null}
    </div>
  );
}
