import { createContext, useContext, useMemo, type ReactNode } from 'react';

import { useAuth } from '../auth/AuthProvider';
import { createApiClient, type ApiClient } from './api';

const ApiContext = createContext<ApiClient | null>(null);

export function ApiProvider({ children }: { children: ReactNode }) {
  const { client, endSession } = useAuth();
  const api = useMemo(
    () =>
      createApiClient({
        getAccessToken: async () => (await client.getSession())?.accessToken ?? null,
        onUnauthorized: () => endSession('expired'),
      }),
    [client, endSession],
  );
  return <ApiContext.Provider value={api}>{children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  const api = useContext(ApiContext);
  if (!api) {
    throw new Error('useApi must be used inside ApiProvider');
  }
  return api;
}
