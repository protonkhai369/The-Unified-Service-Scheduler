import { vi } from 'vitest';

import { InvalidCredentialsError, type AuthClient, type Session } from '../auth/authClient';

export const PASSWORD = 'correct-password';

export function fakeAuth({ signedIn = true, email = 'emma.wilson@example.com' } = {}) {
  let session: Session | null = signedIn ? { accessToken: 'test-token', email } : null;
  const listeners = new Set<(session: Session | null) => void>();
  const notify = () => listeners.forEach((listener) => listener(session));

  const client = {
    getSession: async () => session,
    signIn: vi.fn(async (address: string, password: string) => {
      if (password !== PASSWORD) {
        throw new InvalidCredentialsError();
      }
      session = { accessToken: 'test-token', email: address };
      notify();
      return session;
    }),
    signOut: vi.fn(async () => {
      session = null;
      notify();
    }),
    onChange: (listener: (session: Session | null) => void) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  } satisfies AuthClient;
  return client;
}
