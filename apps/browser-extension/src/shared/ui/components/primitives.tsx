import { clsx } from 'clsx';
import type {
  InputHTMLAttributes,
  PropsWithChildren,
  ReactNode,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react';

export function Page(
  props: PropsWithChildren<{ title: string; subtitle?: string; actions?: ReactNode }>,
) {
  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold text-white">{props.title}</h1>
          {props.subtitle ? <p className="mt-1 text-sm text-slate-300">{props.subtitle}</p> : null}
        </div>
        {props.actions}
      </div>
      {props.children}
    </div>
  );
}

export function Card(
  props: PropsWithChildren<{ className?: string; title?: string; actions?: ReactNode }>,
) {
  return (
    <section
      className={clsx(
        'rounded-2xl border border-slate-700/80 bg-slate-900/60 p-4 shadow-xl shadow-slate-950/20 backdrop-blur',
        props.className,
      )}
    >
      {props.title || props.actions ? (
        <div className="mb-3 flex items-center justify-between gap-3">
          {props.title ? (
            <h2 className="text-sm font-semibold text-white">{props.title}</h2>
          ) : (
            <span />
          )}
          {props.actions}
        </div>
      ) : null}
      {props.children}
    </section>
  );
}

export function Button(
  props: PropsWithChildren<{
    onClick?: () => void;
    type?: 'button' | 'submit';
    variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
    disabled?: boolean;
    className?: string;
  }>,
) {
  const variant = props.variant ?? 'primary';
  return (
    <button
      type={props.type ?? 'button'}
      onClick={props.onClick}
      disabled={props.disabled}
      className={clsx(
        'inline-flex items-center justify-center rounded-xl px-3 py-2 text-sm font-medium transition',
        variant === 'primary' &&
          'bg-blue-500 text-slate-950 hover:bg-blue-400 disabled:bg-slate-700 disabled:text-slate-400',
        variant === 'secondary' &&
          'bg-slate-800 text-slate-100 hover:bg-slate-700 disabled:bg-slate-900 disabled:text-slate-500',
        variant === 'danger' &&
          'bg-rose-500 text-white hover:bg-rose-400 disabled:bg-slate-700 disabled:text-slate-400',
        variant === 'ghost' &&
          'bg-transparent text-slate-200 hover:bg-slate-800 disabled:text-slate-500',
        props.className,
      )}
    >
      {props.children}
    </button>
  );
}

export function Label(props: PropsWithChildren) {
  return (
    <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-slate-300">
      {props.children}
    </label>
  );
}

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={clsx(
        'w-full rounded-xl border border-slate-700 bg-slate-950/70 px-3 py-2 text-sm text-white outline-none ring-0 transition placeholder:text-slate-500 focus:border-blue-400',
        props.className,
      )}
    />
  );
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      {...props}
      className={clsx(
        'w-full rounded-xl border border-slate-700 bg-slate-950/70 px-3 py-2 text-sm text-white outline-none transition focus:border-blue-400',
        props.className,
      )}
    />
  );
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      {...props}
      className={clsx(
        'w-full rounded-xl border border-slate-700 bg-slate-950/70 px-3 py-2 text-sm text-white outline-none transition placeholder:text-slate-500 focus:border-blue-400',
        props.className,
      )}
    />
  );
}

export function Field(props: PropsWithChildren<{ label: string; description?: string }>) {
  return (
    <div className="space-y-1">
      <Label>{props.label}</Label>
      {props.children}
      {props.description ? <p className="text-xs text-slate-400">{props.description}</p> : null}
    </div>
  );
}

export function Badge(
  props: PropsWithChildren<{ tone?: 'default' | 'success' | 'warn' | 'danger' }>,
) {
  const tone = props.tone ?? 'default';
  return (
    <span
      className={clsx(
        'inline-flex rounded-full px-2 py-1 text-xs font-medium',
        tone === 'default' && 'bg-slate-800 text-slate-200',
        tone === 'success' && 'bg-emerald-500/20 text-emerald-300',
        tone === 'warn' && 'bg-amber-500/20 text-amber-300',
        tone === 'danger' && 'bg-rose-500/20 text-rose-300',
      )}
    >
      {props.children}
    </span>
  );
}

export function InlineError(props: { message?: string | undefined }) {
  if (!props.message) return null;
  return <p className="text-sm text-rose-300">{props.message}</p>;
}

export function EmptyState(props: { title: string; description: string }) {
  return (
    <div className="rounded-xl border border-dashed border-slate-700 p-4 text-sm text-slate-300">
      <p className="font-medium text-white">{props.title}</p>
      <p className="mt-1 text-slate-400">{props.description}</p>
    </div>
  );
}
