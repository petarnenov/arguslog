import { ArguslogErrorBoundary } from '@arguslog/sdk-react';
import { useState } from 'react';

import { ErrorFallback } from '../components/ErrorFallback.js';

function BrokenChild({ trigger }: { trigger: number }) {
  if (trigger > 0) {
    throw new Error(`BrokenChild rendered while trigger=${trigger}`);
  }
  return <p>BrokenChild is healthy. Click the button to break it.</p>;
}

export function DemoBoundaryPage() {
  // Local boundary scoped to this demo — independent from the top-level boundary in App.tsx.
  // Nesting boundaries is the React-recommended pattern: catch errors close to where they
  // happen so the rest of the UI keeps rendering.
  const [trigger, setTrigger] = useState(0);

  return (
    <div>
      <h1>ArguslogErrorBoundary</h1>
      <p>
        Catches render-time exceptions thrown inside the boundary, reports them to Arguslog with the
        <code> boundary:react </code> tag, and shows the supplied fallback. The fallback's reset
        callback restores the boundary's children — so transient errors don't leave the section
        stuck.
      </p>
      <p>
        The boundary at the root of <code>App.tsx</code> wraps everything; this page demonstrates a
        nested boundary that contains the error to one subtree.
      </p>
      <ArguslogErrorBoundary
        fallback={({ error, reset }) => (
          <ErrorFallback
            error={error}
            reset={() => {
              setTrigger(0);
              reset();
            }}
          />
        )}
      >
        <BrokenChild trigger={trigger} />
      </ArguslogErrorBoundary>
      <div className="row" style={{ marginTop: '1rem' }}>
        <button type="button" onClick={() => setTrigger((n) => n + 1)}>
          Break child component
        </button>
      </div>
    </div>
  );
}
