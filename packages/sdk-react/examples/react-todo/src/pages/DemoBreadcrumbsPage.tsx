import { useArguslog } from '@arguslog/sdk-react';
import { useState } from 'react';

export function DemoBreadcrumbsPage() {
  const arguslog = useArguslog();
  const [trail, setTrail] = useState<string[]>([]);

  const dropFive = () => {
    const steps = [
      { category: 'ui.click', message: 'Opened cart' },
      { category: 'ui.click', message: 'Edited quantity to 2' },
      { category: 'navigation', message: 'Navigated to /checkout' },
      { category: 'api', message: 'POST /api/checkout (200)' },
      { category: 'ui.click', message: 'Clicked Pay' },
    ];
    for (const step of steps) {
      arguslog.addBreadcrumb({
        category: step.category,
        message: step.message,
        level: 'info',
      });
    }
    setTrail(steps.map((s) => `[${s.category}] ${s.message}`));
  };

  const fireWithTrail = () => {
    arguslog.captureMessage('Trail-attached event', 'warning');
  };

  return (
    <div>
      <h1>addBreadcrumb</h1>
      <p>
        Breadcrumbs are tiny, ordered events that build a timeline up to a captured event — clicks,
        navigations, fetches. They're attached to the next event the SDK sends; on the dashboard
        they render as a chronological feed leading to the error.
      </p>
      <div className="row">
        <button type="button" onClick={dropFive}>
          Drop 5 breadcrumbs
        </button>
        <button type="button" onClick={fireWithTrail}>
          Fire event (carries the trail)
        </button>
      </div>
      {trail.length > 0 ? (
        <ol className="muted">
          {trail.map((line) => (
            <li key={line}>
              <code>{line}</code>
            </li>
          ))}
        </ol>
      ) : null}
      <p className="muted">
        The <code>autoBreadcrumbs</code> integration in <code>arguslog.ts</code> already records
        console, fetch, history, DOM clicks, web vitals, long tasks and more — you usually only
        need <code>addBreadcrumb</code> for app-specific signal the auto-instrumentation can't see.
      </p>
    </div>
  );
}
