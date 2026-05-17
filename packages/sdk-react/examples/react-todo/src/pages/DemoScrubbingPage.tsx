import { useArguslog } from '@arguslog/sdk-react';
import { useState } from 'react';

export function DemoScrubbingPage() {
  const arguslog = useArguslog();
  const [token, setToken] = useState('todo_secret_abc123xyz789');
  const [apiKey, setApiKey] = useState('demo-api-key-zxy987');
  const [fired, setFired] = useState(false);

  const fire = () => {
    arguslog.setContext('credentials', {
      token,
      apiKey,
      visible: 'not-a-secret',
    });
    arguslog.captureMessage('event carrying secret-shaped strings', 'warning');
    setFired(true);
  };

  return (
    <div>
      <h1>PII / secret scrubbing</h1>
      <p>
        The SDK runs every outgoing event through a regex-based scrubber before sending. Default
        patterns mask common shapes (Authorization headers, credit cards, …); the example app adds
        two project-specific patterns in <code>arguslog.ts</code>:
      </p>
      <pre>
        {`extraPatterns: [
  /todo_secret_[a-z0-9]+/gi,
  /demo[_-]?api[_-]?key/gi,
]`}
      </pre>
      <p>
        Fire the button below — on the dashboard you'll see <code>token</code> and{' '}
        <code>apiKey</code> masked while <code>visible</code> goes through unchanged.
      </p>
      <div className="form-grid">
        <label>
          <span>token (matches todo_secret_*)</span>
          <input value={token} onChange={(e) => setToken(e.target.value)} />
        </label>
        <label>
          <span>apiKey (matches demo_api_key)</span>
          <input value={apiKey} onChange={(e) => setApiKey(e.target.value)} />
        </label>
      </div>
      <div className="row">
        <button type="button" onClick={fire}>
          Fire event with secrets
        </button>
      </div>
      {fired ? (
        <p className="muted">
          Event sent. The values you typed never left the SDK — the scrubber rewrote them to{' '}
          <code>[Filtered]</code> before the network payload was built.
        </p>
      ) : null}
    </div>
  );
}
