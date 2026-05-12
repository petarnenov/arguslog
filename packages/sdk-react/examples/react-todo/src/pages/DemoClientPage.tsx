import { getClient, parseDsn, type ArguslogClient } from '@arguslog/sdk-react';
import { useArguslog } from '@arguslog/sdk-react';
import { useMemo } from 'react';

export function DemoClientPage() {
  const arguslog = useArguslog();
  const dsn = import.meta.env.VITE_ARGUSLOG_DSN;
  const client: ArguslogClient | undefined = getClient();

  const parsed = useMemo(() => {
    try {
      return dsn ? parseDsn(dsn) : null;
    } catch (e) {
      return { error: (e as Error).message };
    }
  }, [dsn]);

  return (
    <div>
      <h1>Client introspection</h1>
      <p>
        Two read-only helpers expose what the SDK currently looks like.{' '}
        <code>getClient()</code> returns the active <code>ArguslogClient</code> (or
        <code> undefined </code> if <code>init</code> wasn't called).{' '}
        <code>parseDsn(dsn)</code> validates a DSN string and breaks it into its components without
        any side-effect.
      </p>

      <h3>useArguslog().isInitialized()</h3>
      <pre>
        {JSON.stringify({ isInitialized: arguslog.isInitialized() }, null, 2)}
      </pre>

      <h3>getClient() identity</h3>
      <pre>
        {JSON.stringify(
          {
            present: Boolean(client),
            constructor: client?.constructor.name,
          },
          null,
          2,
        )}
      </pre>

      <h3>parseDsn(VITE_ARGUSLOG_DSN)</h3>
      <pre>{JSON.stringify(parsed, null, 2)}</pre>
    </div>
  );
}
