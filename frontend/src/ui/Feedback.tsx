import { Loader2 } from 'lucide-react';
import type { ReactNode } from 'react';

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <span role="status" className="inline-flex items-center gap-2 text-slate">
      <Loader2 aria-hidden="true" className="size-5 animate-spin text-ocean" />
      <span>{label}…</span>
    </span>
  );
}

type EmptyStateProps = {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
};

export function EmptyState({ title, children, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-start gap-2 rounded-lg border border-dashed border-field px-4 py-6">
      <p className="font-medium text-ink">{title}</p>
      {children && <div className="text-slate">{children}</div>}
      {action}
    </div>
  );
}
