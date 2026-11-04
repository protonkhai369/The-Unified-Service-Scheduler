import { CircleAlert, CircleCheck, Info } from 'lucide-react';
import type { ReactNode } from 'react';

type Tone = 'error' | 'info' | 'success';

const tones: Record<Tone, { border: string; icon: ReactNode }> = {
  error: { border: 'border-danger', icon: <CircleAlert aria-hidden="true" className="size-5 shrink-0 text-danger" /> },
  info: { border: 'border-ocean', icon: <Info aria-hidden="true" className="size-5 shrink-0 text-ocean" /> },
  success: { border: 'border-ocean', icon: <CircleCheck aria-hidden="true" className="size-5 shrink-0 text-ocean" /> },
};

type AlertProps = {
  tone: Tone;
  children: ReactNode;
  action?: ReactNode;
};

export function Alert({ tone, children, action }: AlertProps) {
  return (
    <div role={tone === 'error' ? 'alert' : 'status'} className={`flex items-start gap-3 rounded-lg border-l-4 bg-mist px-4 py-3 text-ink ${tones[tone].border}`}>
      {tones[tone].icon}
      <div className="flex flex-1 flex-col gap-2">
        <div>{children}</div>
        {action}
      </div>
    </div>
  );
}
