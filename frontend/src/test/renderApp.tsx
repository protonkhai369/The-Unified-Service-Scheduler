import { QueryClient } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';

import { AppContent, AppProviders } from '../App';
import type { AuthClient } from '../auth/authClient';
import { fakeAuth } from './fakeAuth';

export function renderApp(path: string, { auth = fakeAuth() }: { auth?: AuthClient } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const user = userEvent.setup();
  render(
    <AppProviders auth={auth} queryClient={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <AppContent />
      </MemoryRouter>
    </AppProviders>,
  );
  return { user, auth, queryClient };
}
