import { useArguslog } from '@arguslog/sdk-react';
import { useState } from 'react';
import type { Level } from '@arguslog/sdk-react';

const LEVELS: Level[] = ['debug', 'info', 'warning', 'error', 'fatal'];

export function DemoLevelsPage() {
  const arguslog = useArguslog();
  const [fired, setFired] = useState<Array<{ level: Level; id?: string }>>([]);

  const fireAll = () => {
    const results = LEVELS.map((level) => ({
      level,
      id: arguslog.captureMessage(`Demo event at level=${level}`, level),
    }));
    setFired(results);
  };

  return (
    <div>
      <h1>Severity levels</h1>
      <p>
        Fires one event per level so you can verify level filtering on the dashboard and in alert
        rules. Levels are ordered: <code>debug &lt; info &lt; warning &lt; error &lt; fatal</code>.
      </p>
      <button type="button" onClick={fireAll}>
        Fire one event per level
      </button>
      {fired.length > 0 ? (
        <ul className="muted">
          {fired.map((f) => (
            <li key={f.level}>
              <code>{f.level}</code> → <code>{f.id ?? '(dropped)'}</code>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
