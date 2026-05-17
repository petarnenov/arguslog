import { useArguslog } from '@arguslog/sdk-react';
import { useEffect, useState } from 'react';

interface Todo {
  id: string;
  text: string;
  done: boolean;
}

const STORAGE_KEY = 'arguslog-demo-todos';

function loadTodos(): Todo[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Todo[]) : [];
  } catch {
    return [];
  }
}

function saveTodos(todos: Todo[]): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(todos));
}

export function HomePage() {
  // useArguslog is the React-flavoured handle on the SDK — same surface as the bare imports,
  // memoised once so referential equality holds across renders.
  const arguslog = useArguslog();
  const [todos, setTodos] = useState<Todo[]>(() => loadTodos());
  const [draft, setDraft] = useState('');

  useEffect(() => {
    saveTodos(todos);
  }, [todos]);

  useEffect(() => {
    arguslog.captureMessage('todo-list mounted', 'info');
  }, [arguslog]);

  const handleAdd = () => {
    const text = draft.trim();
    if (!text) return;
    const todo: Todo = { id: crypto.randomUUID(), text, done: false };
    setTodos((prev) => [...prev, todo]);
    setDraft('');
    arguslog.addBreadcrumb({
      category: 'todo',
      message: 'todo.add',
      level: 'info',
      data: { id: todo.id, length: text.length },
    });
  };

  const handleToggle = (id: string) => {
    setTodos((prev) => prev.map((t) => (t.id === id ? { ...t, done: !t.done } : t)));
    arguslog.addBreadcrumb({
      category: 'todo',
      message: 'todo.toggle',
      level: 'info',
      data: { id },
    });
  };

  const handleDelete = (id: string) => {
    setTodos((prev) => prev.filter((t) => t.id !== id));
    arguslog.addBreadcrumb({
      category: 'todo',
      message: 'todo.delete',
      level: 'info',
      data: { id },
    });
  };

  // Demo: a broken save path. Wrapped in try/catch so we can report it without crashing the page.
  const handleBrokenSave = () => {
    try {
      // Simulates a misuse that throws synchronously — JSON.parse rejects malformed input.
      JSON.parse('{not-json');
    } catch (err) {
      arguslog.captureException(err, { tags: { feature: 'todo-save' } });
      alert(
        'A handled error was reported to Arguslog (see captureException demo for the same pattern).',
      );
    }
  };

  return (
    <div>
      <h1>TODO</h1>
      <p className="muted">
        Every action below leaves a breadcrumb. Trigger any error-emitting demo afterwards and the
        event will carry this trail.
      </p>

      <div className="row">
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleAdd();
          }}
          placeholder="What needs doing?"
          aria-label="New todo"
        />
        <button type="button" onClick={handleAdd}>
          Add
        </button>
        <button type="button" onClick={handleBrokenSave} className="ghost">
          Trigger handled error
        </button>
      </div>

      {todos.length === 0 ? (
        <p className="muted">No todos yet — add one to drop a breadcrumb.</p>
      ) : (
        <ul className="todo-list">
          {todos.map((todo) => (
            <li key={todo.id} className={todo.done ? 'done' : ''}>
              <label>
                <input type="checkbox" checked={todo.done} onChange={() => handleToggle(todo.id)} />
                <span>{todo.text}</span>
              </label>
              <button type="button" onClick={() => handleDelete(todo.id)} aria-label="Delete">
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
