import { useArguslog } from '@arguslog/sdk-react';
import { useState } from 'react';

export function DemoUserPage() {
  const arguslog = useArguslog();
  const [id, setId] = useState('demo-user-42');
  const [email, setEmail] = useState('demo@example.com');
  const [username, setUsername] = useState('demo');
  const [identified, setIdentified] = useState<string | null>(null);

  const identify = () => {
    arguslog.setUser({ id, email, username });
    setIdentified(`${username} <${email}>`);
    arguslog.captureMessage('user identified', 'info');
  };

  const clear = () => {
    arguslog.setUser(undefined);
    setIdentified(null);
    arguslog.captureMessage('user cleared', 'info');
  };

  return (
    <div>
      <h1>setUser</h1>
      <p>
        Attaches user identity to every subsequent event until cleared. Lets you answer "which user
        hit this error?" on the dashboard. Pass <code>undefined</code> to forget the user (logout
        flow).
      </p>
      <div className="form-grid">
        <label>
          <span>id</span>
          <input value={id} onChange={(e) => setId(e.target.value)} />
        </label>
        <label>
          <span>email</span>
          <input value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          <span>username</span>
          <input value={username} onChange={(e) => setUsername(e.target.value)} />
        </label>
      </div>
      <div className="row">
        <button type="button" onClick={identify}>
          setUser
        </button>
        <button type="button" onClick={clear} className="ghost">
          Clear user
        </button>
      </div>
      <p className="muted">
        Status: {identified ? <code>{identified}</code> : <em>anonymous</em>}
      </p>
    </div>
  );
}
