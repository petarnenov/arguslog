import { useArguslog } from '@arguslog/sdk-react';
import { useState } from 'react';
import type { Level } from '@arguslog/sdk-react';

const LEVELS: Level[] = ['debug', 'info', 'warning', 'error', 'fatal'];

export function DemoCaptureMessagePage() {
  const arguslog = useArguslog();
  const [text, setText] = useState('User reached the checkout step');
  const [level, setLevel] = useState<Level>('info');
  const [lastId, setLastId] = useState<string | undefined>();

  const fire = () => {
    const id = arguslog.captureMessage(text || '(empty)', level);
    setLastId(id);
  };

  return (
    <div>
      <h1>captureMessage</h1>
      <p>
        Records a structured, stack-less event. Good for milestones ("checkout reached"), business
        signals, and warnings that don't come from caught exceptions.
      </p>
      <div className="row">
        <input value={text} onChange={(e) => setText(e.target.value)} aria-label="Message body" />
        <select value={level} onChange={(e) => setLevel(e.target.value as Level)}>
          {LEVELS.map((l) => (
            <option key={l} value={l}>
              {l}
            </option>
          ))}
        </select>
        <button type="button" onClick={fire}>
          Capture
        </button>
      </div>
      {lastId ? (
        <p className="muted">
          Captured. Event id: <code>{lastId}</code>
        </p>
      ) : null}
    </div>
  );
}
