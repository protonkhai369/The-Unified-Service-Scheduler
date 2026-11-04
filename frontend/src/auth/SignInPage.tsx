import { CalendarCheck } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router';

import { Alert, Button, TextField } from '../ui';
import { InvalidCredentialsError } from './authClient';
import { useAuth } from './AuthProvider';

export function SignInPage() {
  const { status, notice, signIn } = useAuth();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  if (status === 'signedIn') {
    const from = (location.state as { from?: string } | null)?.from;
    return <Navigate to={from ?? '/'} replace />;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      await signIn(email.trim(), password);
    } catch (failure) {
      setError(failure instanceof InvalidCredentialsError ? 'E-mail or password is incorrect.' : (failure as Error).message);
      setBusy(false);
    }
  }

  return (
    <div className="min-h-dvh bg-mist">
      <header className="on-navy bg-navy px-4 py-4 text-white">
        <p className="mx-auto flex max-w-md items-center gap-2 font-semibold">
          <CalendarCheck aria-hidden="true" className="size-5 text-yellow" />
          Service Scheduler
        </p>
      </header>
      <main className="mx-auto max-w-md px-4 py-10">
        <div className="flex flex-col gap-6 rounded-xl bg-white p-6 shadow-sm">
          <div>
            <h1 className="text-2xl font-semibold text-navy">Sign in</h1>
            <p className="mt-1 text-slate">Book and manage dealership service appointments.</p>
          </div>
          {notice === 'expired' && <Alert tone="info">Your session has expired. Please sign in again.</Alert>}
          <form onSubmit={submit} noValidate className="flex flex-col gap-4">
            <TextField label="E-mail" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
            <TextField
              label="Password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            {error && <Alert tone="error">{error}</Alert>}
            <Button type="submit" variant="primary" busy={busy} disabled={!email || !password}>
              Sign in
            </Button>
          </form>
        </div>
      </main>
    </div>
  );
}
