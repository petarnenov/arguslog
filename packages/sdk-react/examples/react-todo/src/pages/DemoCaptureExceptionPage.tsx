import { useArguslog } from '@arguslog/sdk-react';
import { useState } from 'react';

export function DemoCaptureExceptionPage() {
  const arguslog = useArguslog();
  const [lastEventId, setLastEventId] = useState<string | undefined>();

  const fire = () => {
    try {
      // Realistic shape: a parser bug producing a TypeError. Stack lines will be visible on the
      // dashboard and source-mapped to original positions if a matching release tag has been
      // uploaded.
      const parsed = JSON.parse('null');
      // Realistic shape: assume the response had a user object that turned out to be null.
      parsed.user.email = 'oops';
    } catch (err) {
      const id = arguslog.captureException(err, {
        level: 'error',
        tags: { feature: 'demo:capture-exception' },
      });
      setLastEventId(id);
    }
  };

  return (
    <div>
      <h1>captureException</h1>
      <p>
        Reports a caught error to Arguslog with full stack frames. Use this for try/catch sites
        where the user's flow can continue but something went wrong.
      </p>
      <button type="button" onClick={fire}>
        Fire handled TypeError
      </button>
      {lastEventId ? (
        <p className="muted">
          Reported. Event id: <code>{lastEventId}</code>
        </p>
      ) : null}
    </div>
  );
}
