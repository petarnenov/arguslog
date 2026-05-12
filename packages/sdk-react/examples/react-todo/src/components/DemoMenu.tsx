import { NavLink } from 'react-router';

const LINKS: Array<{ to: string; label: string; group: string }> = [
  { to: '/', label: 'TODO list (useArguslog + breadcrumbs)', group: 'App' },
  { to: '/demo/capture-exception', label: 'captureException()', group: 'Capture' },
  { to: '/demo/capture-message', label: 'captureMessage()', group: 'Capture' },
  { to: '/demo/levels', label: 'All severity levels', group: 'Capture' },
  { to: '/demo/boundary', label: 'ErrorBoundary fallback', group: 'Capture' },
  { to: '/demo/unhandled-sync', label: 'Unhandled sync error', group: 'Globals' },
  { to: '/demo/unhandled-async', label: 'Unhandled promise rejection', group: 'Globals' },
  { to: '/demo/user', label: 'setUser() / clear', group: 'Scope' },
  { to: '/demo/tags', label: 'setTag()', group: 'Scope' },
  { to: '/demo/context', label: 'setContext()', group: 'Scope' },
  { to: '/demo/breadcrumbs', label: 'addBreadcrumb()', group: 'Scope' },
  { to: '/demo/scrubbing', label: 'PII scrubbing', group: 'Privacy' },
  { to: '/demo/before-send', label: 'beforeSend filter', group: 'Privacy' },
  { to: '/demo/flush', label: 'flush() before unload', group: 'Lifecycle' },
  { to: '/demo/client', label: 'getClient() introspection', group: 'Lifecycle' },
];

export function DemoMenu() {
  const groups = LINKS.reduce<Record<string, typeof LINKS>>((acc, link) => {
    (acc[link.group] ??= []).push(link);
    return acc;
  }, {});

  return (
    <nav className="sidebar">
      <h1 className="sidebar-title">Arguslog React demo</h1>
      <p className="sidebar-hint">
        Each link below exercises one part of the SDK. Open the Arguslog dashboard side-by-side and
        click through — every action emits an event you can verify.
      </p>
      {Object.entries(groups).map(([group, links]) => (
        <div className="sidebar-group" key={group}>
          <h2 className="sidebar-group-title">{group}</h2>
          <ul>
            {links.map((link) => (
              <li key={link.to}>
                <NavLink
                  to={link.to}
                  end={link.to === '/'}
                  className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
                >
                  {link.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}
