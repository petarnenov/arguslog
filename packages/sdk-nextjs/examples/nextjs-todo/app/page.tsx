'use client';

import { useArguslog } from '@arguslog/sdk-nextjs/client';
import { useEffect, useState } from 'react';

type Todo = { id: string; text: string; done: boolean };

export default function HomePage() {
  const arguslog = useArguslog();
  const [todos, setTodos] = useState<Todo[]>([]);
  const [draft, setDraft] = useState('');
  const [loading, setLoading] = useState(true);
  const [explode, setExplode] = useState(false);

  if (explode) throw new Error('Demo: error thrown from <HomePage /> render');

  useEffect(() => {
    fetch('/api/todos')
      .then((r) => r.json())
      .then((data: { todos: Todo[] }) => setTodos(data.todos))
      .catch((err) => arguslog.captureException(err, { tags: { route: 'GET /api/todos' } }))
      .finally(() => setLoading(false));
  }, [arguslog]);

  async function addTodo(text: string) {
    arguslog.addBreadcrumb({ category: 'todo', message: 'add', level: 'info' });
    const res = await fetch('/api/todos', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text }),
    });
    const data = (await res.json()) as { todo: Todo };
    setTodos((cur) => [...cur, data.todo]);
  }

  async function toggleTodo(id: string) {
    arguslog.addBreadcrumb({ category: 'todo', message: 'toggle', level: 'info', data: { id } });
    const res = await fetch(`/api/todos?id=${encodeURIComponent(id)}`, { method: 'PATCH' });
    const data = (await res.json()) as { todo: Todo };
    setTodos((cur) => cur.map((t) => (t.id === id ? data.todo : t)));
  }

  async function deleteTodo(id: string) {
    arguslog.addBreadcrumb({ category: 'todo', message: 'delete', level: 'info', data: { id } });
    await fetch(`/api/todos?id=${encodeURIComponent(id)}`, { method: 'DELETE' });
    setTodos((cur) => cur.filter((t) => t.id !== id));
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    const text = draft.trim();
    if (!text) return;
    setDraft('');
    void addTodo(text);
  }

  return (
    <main className="container">
      <header>
        <h1>Arguslog Next.js TODO</h1>
        <p className="hint">
          Server stores todos in-memory, fetched via a <code>wrapRouteHandler</code>-wrapped API
          route. Open DevTools to see breadcrumbs and watch events arrive in your Arguslog
          dashboard.
        </p>
      </header>

      <form onSubmit={onSubmit} className="add">
        <input
          aria-label="New todo"
          placeholder="What needs doing?"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
        <button type="submit" disabled={!draft.trim()}>
          Add
        </button>
      </form>

      {loading ? (
        <p>Loading…</p>
      ) : todos.length === 0 ? (
        <p className="empty">No todos yet — add one above.</p>
      ) : (
        <ul className="todos">
          {todos.map((t) => (
            <li key={t.id} className={t.done ? 'done' : ''}>
              <label>
                <input type="checkbox" checked={t.done} onChange={() => void toggleTodo(t.id)} />
                <span>{t.text}</span>
              </label>
              <button onClick={() => void deleteTodo(t.id)} aria-label={`Delete ${t.text}`}>
                ×
              </button>
            </li>
          ))}
        </ul>
      )}

      <section className="demos">
        <h2>Verify SDK wiring</h2>
        <div className="demo-row">
          <button
            onClick={() =>
              arguslog.captureException(new Error('Demo: handled exception from button'), {
                tags: { demo: 'capture-exception' },
              })
            }
          >
            captureException()
          </button>
          <button onClick={() => setExplode(true)}>Throw render error (boundary)</button>
          <button
            onClick={() =>
              fetch('/api/todos?fail=1').catch(() => {
                /* expected — the route handler throws and Next renders 500 */
              })
            }
          >
            Trigger server error
          </button>
        </div>
      </section>
    </main>
  );
}
