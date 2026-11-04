import { Loader2 } from 'lucide-react';
import type { ButtonHTMLAttributes } from 'react';

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost';

const variants: Record<Variant, string> = {
  primary: 'bg-yellow text-ink hover:brightness-95',
  secondary: 'border border-ocean bg-white text-ocean hover:bg-mist',
  danger: 'border border-danger bg-white text-danger hover:bg-mist',
  ghost: 'text-white hover:bg-white/10',
};

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
  busy?: boolean;
};

export function Button({ variant = 'secondary', busy = false, disabled, type = 'button', className = '', children, ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      data-variant={variant}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-lg px-4 font-medium transition-[filter,background-color] duration-150 disabled:cursor-not-allowed disabled:opacity-60 ${variants[variant]} ${className}`}
      {...rest}
    >
      {busy && <Loader2 aria-hidden="true" className="size-4 animate-spin" />}
      {children}
    </button>
  );
}
