import { useArguslog } from '@arguslog/sdk-react';
import { useState } from 'react';

export function DemoTagsPage() {
  const arguslog = useArguslog();
  const [key, setKey] = useState('feature-flag');
  const [value, setValue] = useState('new-onboarding');
  const [applied, setApplied] = useState<Array<[string, string]>>([]);

  const apply = () => {
    arguslog.setTag(key, value);
    setApplied((prev) => [...prev.filter(([k]) => k !== key), [key, value]]);
  };

  const fire = () => {
    arguslog.captureMessage('event tagged via setTag()', 'info');
  };

  return (
    <div>
      <h1>setTag</h1>
      <p>
        Tags are short, indexed key/value pairs that the dashboard uses for filtering. Set them
        globally for cross-cutting facts (release channel, feature flag, A/B variant) and override
        per-event for event-specific signal.
      </p>
      <div className="row">
        <input value={key} onChange={(e) => setKey(e.target.value)} aria-label="Tag key" />
        <input value={value} onChange={(e) => setValue(e.target.value)} aria-label="Tag value" />
        <button type="button" onClick={apply}>
          setTag
        </button>
        <button type="button" onClick={fire}>
          Fire event
        </button>
      </div>
      {applied.length > 0 ? (
        <ul className="muted">
          {applied.map(([k, v]) => (
            <li key={k}>
              <code>
                {k} = {v}
              </code>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
