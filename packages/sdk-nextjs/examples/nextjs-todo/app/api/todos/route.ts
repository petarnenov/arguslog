import { wrapRouteHandler } from '@arguslog/sdk-nextjs/server';
import { NextResponse } from 'next/server';

type Todo = { id: string; text: string; done: boolean };

const todos: Todo[] = [];

function newId(): string {
  return `t_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

export const GET = wrapRouteHandler(async (req: Request) => {
  const url = new URL(req.url);
  if (url.searchParams.get('fail') === '1') {
    throw new Error('Demo: forced GET /api/todos failure');
  }
  return NextResponse.json({ todos });
});

export const POST = wrapRouteHandler(async (req: Request) => {
  const body = (await req.json()) as { text?: unknown };
  if (typeof body.text !== 'string' || body.text.trim().length === 0) {
    return NextResponse.json({ error: 'text required' }, { status: 400 });
  }
  const todo: Todo = { id: newId(), text: body.text.trim(), done: false };
  todos.push(todo);
  return NextResponse.json({ todo }, { status: 201 });
});

export const PATCH = wrapRouteHandler(async (req: Request) => {
  const id = new URL(req.url).searchParams.get('id');
  const todo = todos.find((t) => t.id === id);
  if (!todo) return NextResponse.json({ error: 'not found' }, { status: 404 });
  todo.done = !todo.done;
  return NextResponse.json({ todo });
});

export const DELETE = wrapRouteHandler(async (req: Request) => {
  const id = new URL(req.url).searchParams.get('id');
  const idx = todos.findIndex((t) => t.id === id);
  if (idx === -1) return NextResponse.json({ error: 'not found' }, { status: 404 });
  todos.splice(idx, 1);
  return NextResponse.json({ ok: true });
});
