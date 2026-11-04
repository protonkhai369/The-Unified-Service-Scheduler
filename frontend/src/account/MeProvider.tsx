import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, type ReactNode } from 'react';

import { useApi } from '../lib/ApiProvider';
import { ErrorAlert } from '../lib/ErrorAlert';
import type { Account, Role } from '../lib/types';
import { Spinner } from '../ui';

const AccountContext = createContext<Account | null>(null);

export function MeProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const me = useQuery({ queryKey: ['me'], queryFn: () => api.get<Account>('/api/v1/me') });

  if (me.isPending) {
    return (
      <div className="grid min-h-dvh place-items-center">
        <Spinner />
      </div>
    );
  }
  if (me.isError) {
    return (
      <div className="mx-auto max-w-md px-4 py-10">
        <ErrorAlert error={me.error} />
      </div>
    );
  }
  return <AccountContext.Provider value={me.data}>{children}</AccountContext.Provider>;
}

export function useAccount(): Account {
  const account = useContext(AccountContext);
  if (!account) {
    throw new Error('useAccount must be used inside MeProvider');
  }
  return account;
}

export function usableRole(account: Account): Role | null {
  switch (account.role) {
    case 'ADMIN':
      return 'ADMIN';
    case 'CUSTOMER':
      return account.customer ? 'CUSTOMER' : null;
    case 'ADVISOR':
      return account.dealership ? 'ADVISOR' : null;
    default:
      return null;
  }
}
