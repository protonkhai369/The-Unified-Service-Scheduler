import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react';

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

type DialogProps = {
  title: string;
  onClose: () => void;
  children: ReactNode;
};

export function Dialog({ title, onClose, children }: DialogProps) {
  const panel = useRef<HTMLDivElement>(null);
  const titleId = useId();

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    panel.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus();
    return () => previous?.focus();
  }, []);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== 'Tab' || !panel.current) {
      return;
    }
    const items = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    const first = items[0];
    const last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-ink/50 p-4 sm:items-center">
      <div ref={panel} role="dialog" aria-modal="true" aria-labelledby={titleId} onKeyDown={onKeyDown} className="w-full max-w-md rounded-xl bg-white p-6 shadow-lg">
        <h2 id={titleId} className="text-xl font-semibold text-navy">
          {title}
        </h2>
        <div className="mt-4 flex flex-col gap-4">{children}</div>
      </div>
    </div>
  );
}
