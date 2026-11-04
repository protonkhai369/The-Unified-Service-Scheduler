import '@fontsource-variable/inter';
import './styles/index.css';

import { QueryClient } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './App';
import { supabaseAuthClient } from './auth/authClient';
import { ApiError } from './lib/api';

const auth = supabaseAuthClient(import.meta.env.VITE_SUPABASE_URL, import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY);

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failures, error) => !(error instanceof ApiError && error.status > 0 && error.status < 500) && failures < 2,
      refetchOnWindowFocus: false,
    },
    mutations: { retry: false },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App auth={auth} queryClient={queryClient} />
  </StrictMode>,
);
