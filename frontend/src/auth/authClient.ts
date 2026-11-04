import { createClient } from '@supabase/supabase-js';

export type Session = { accessToken: string; email: string };

export class InvalidCredentialsError extends Error {}

export interface AuthClient {
  getSession(): Promise<Session | null>;
  signIn(email: string, password: string): Promise<Session>;
  signOut(): Promise<void>;
  onChange(listener: (session: Session | null) => void): () => void;
}

export function supabaseAuthClient(url: string | undefined, publishableKey: string | undefined): AuthClient {
  if (!url || !publishableKey) {
    return unconfiguredAuthClient();
  }
  const supabase = createClient(url, publishableKey);
  const toSession = (session: { access_token: string; user: { email?: string } } | null): Session | null =>
    session ? { accessToken: session.access_token, email: session.user.email ?? '' } : null;

  return {
    async getSession() {
      const { data } = await supabase.auth.getSession();
      return toSession(data.session);
    },
    async signIn(email, password) {
      const { data, error } = await supabase.auth.signInWithPassword({ email, password });
      if (error || !data.session) {
        if (error && error.status !== undefined && error.status >= 500) {
          throw error;
        }
        throw new InvalidCredentialsError();
      }
      return toSession(data.session)!;
    },
    async signOut() {
      await supabase.auth.signOut();
    },
    onChange(listener) {
      const { data } = supabase.auth.onAuthStateChange((_event, session) => listener(toSession(session)));
      return () => data.subscription.unsubscribe();
    },
  };
}

function unconfiguredAuthClient(): AuthClient {
  return {
    getSession: async () => null,
    signIn: async () => {
      throw new Error('Sign-in is not configured: set VITE_SUPABASE_URL and VITE_SUPABASE_PUBLISHABLE_KEY in frontend/.env.local.');
    },
    signOut: async () => {},
    onChange: () => () => {},
  };
}
