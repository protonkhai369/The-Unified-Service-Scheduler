import type { ReactNode } from 'react';

type OptionCardProps = {
  title: ReactNode;
  description?: ReactNode;
  selected: boolean;
  onSelect: () => void;
  className?: string;
};

export function OptionCard({ title, description, selected, onSelect, className = '' }: OptionCardProps) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onSelect}
      className={`flex min-h-11 w-full cursor-pointer flex-col items-start gap-0.5 rounded-lg px-4 py-3 text-left transition-colors duration-150 ${
        selected ? 'border-2 border-ink bg-yellow text-ink' : 'border border-field bg-white text-ink hover:bg-mist'
      } ${className}`}
    >
      <span className="font-medium">{title}</span>
      {description && <span className={`text-sm ${selected ? 'text-ink' : 'text-slate'}`}>{description}</span>}
    </button>
  );
}
