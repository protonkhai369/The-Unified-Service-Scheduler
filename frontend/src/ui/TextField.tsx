import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from 'react';

type FieldParts = {
  label: string;
  hint?: ReactNode;
  error?: string;
};

function useFieldIds(id: string | undefined, hint: unknown, error: unknown) {
  const generated = useId();
  const fieldId = id ?? generated;
  const hintId = hint ? `${fieldId}-hint` : undefined;
  const errorId = error ? `${fieldId}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;
  return { fieldId, hintId, errorId, describedBy };
}

function FieldFrame({ label, hint, error, ids, children }: FieldParts & { ids: ReturnType<typeof useFieldIds>; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={ids.fieldId} className="text-sm font-medium text-ink">
        {label}
      </label>
      {children}
      {hint && (
        <p id={ids.hintId} className="text-sm text-slate">
          {hint}
        </p>
      )}
      {error && (
        <p id={ids.errorId} role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  );
}

const control = 'min-h-11 w-full rounded-lg border border-field bg-white px-3 text-base text-ink aria-[invalid=true]:border-danger';

export function TextField({ label, hint, error, id, className = '', ...input }: FieldParts & InputHTMLAttributes<HTMLInputElement>) {
  const ids = useFieldIds(id, hint, error);
  return (
    <FieldFrame label={label} hint={hint} error={error} ids={ids}>
      <input id={ids.fieldId} aria-invalid={error ? true : undefined} aria-describedby={ids.describedBy} className={`${control} ${className}`} {...input} />
    </FieldFrame>
  );
}

export function SelectField({ label, hint, error, id, className = '', children, ...select }: FieldParts & SelectHTMLAttributes<HTMLSelectElement>) {
  const ids = useFieldIds(id, hint, error);
  return (
    <FieldFrame label={label} hint={hint} error={error} ids={ids}>
      <select id={ids.fieldId} aria-invalid={error ? true : undefined} aria-describedby={ids.describedBy} className={`${control} ${className}`} {...select}>
        {children}
      </select>
    </FieldFrame>
  );
}
