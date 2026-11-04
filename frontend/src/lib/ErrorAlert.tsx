import type { ReactNode } from 'react';

import { Alert } from '../ui';
import { errorMessage } from './errors';

export function ErrorAlert({ error, action }: { error: unknown; action?: ReactNode }) {
  const { text, requestId } = errorMessage(error);
  return (
    <Alert tone="error" action={action}>
      <p>{text}</p>
      {requestId && <p className="text-sm text-slate">Reference for support: {requestId}</p>}
    </Alert>
  );
}
