import { Link, type LinkProps } from 'react-router';

const variants = {
  primary: 'bg-yellow text-ink hover:brightness-95',
  secondary: 'border border-ocean bg-white text-ocean hover:bg-mist',
};

export function LinkButton({ variant = 'secondary', className = '', ...props }: LinkProps & { variant?: keyof typeof variants }) {
  return (
    <Link
      className={`inline-flex min-h-11 items-center justify-center gap-2 rounded-lg px-4 font-medium transition-[filter,background-color] duration-150 ${variants[variant]} ${className}`}
      {...props}
    />
  );
}
