import { useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import type { AuthClient, Session } from './authClient';

type Notice = 'expired' | null;

type AuthState = {
  status: 'loading' | 'signedIn' | 'signedOut';
  session: Session | null;
  notice: Notice;
  client: AuthClient;
  signIn: (email: string, password: string) => Promise<void>;
  endSession: (notice?: Notice) => Promise<void>;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ client, children }: { client: AuthClient; children: ReactNode }) {
  const queryClient = useQueryClient();
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<Notice>(null);

  useEffect(() => {
    let active = true;
    client.getSession().then((current) => {
      if (active) {
        setSession(current);
        setLoading(false);
      }
    });
    const unsubscribe = client.onChange((current) => setSession(current));
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client]);

  const signIn = useCallback(
    async (email: string, password: string) => {
      queryClient.clear();
      const current = await client.signIn(email, password);
      setNotice(null);
      setSession(current);
    },
    [client, queryClient],
  );

  const endSession = useCallback(
    async (reason: Notice = null) => {
      await client.signOut();
      queryClient.clear();
      setNotice(reason);
      setSession(null);
    },
    [client, queryClient],
  );

  const value = useMemo<AuthState>(
    () => ({ status: loading ? 'loading' : session ? 'signedIn' : 'signedOut', session, notice, client, signIn, endSession }),
    [loading, session, notice, client, signIn, endSession],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
