import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router';

import { NotLinkedPage, RequireRole, StartRedirect } from './account/guards';
import { MeProvider } from './account/MeProvider';
import type { AuthClient } from './auth/authClient';
import { AuthProvider } from './auth/AuthProvider';
import { RequireAuth } from './auth/RequireAuth';
import { SignInPage } from './auth/SignInPage';
import { MyAppointmentsPage } from './features/appointments/MyAppointmentsPage';
import { BookServicePage } from './features/booking/BookServicePage';
import { BookForCustomerPage } from './features/customers/BookForCustomerPage';
import { DaySchedulePage } from './features/schedule/DaySchedulePage';
import { AppShell } from './layout/AppShell';
import { ApiProvider } from './lib/ApiProvider';

export function AppProviders({ auth, queryClient, children }: { auth: AuthClient; queryClient: QueryClient; children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider client={auth}>{children}</AuthProvider>
    </QueryClientProvider>
  );
}

export function AppContent() {
  return (
    <ApiProvider>
      <Routes>
        <Route path="/sign-in" element={<SignInPage />} />
        <Route element={<RequireAuth />}>
          <Route
            element={
              <MeProvider>
                <AppShell />
              </MeProvider>
            }
          >
            <Route index element={<StartRedirect />} />
            <Route path="book" element={<RequireRole roles={['CUSTOMER']}><BookServicePage /></RequireRole>} />
            <Route path="appointments" element={<RequireRole roles={['CUSTOMER']}><MyAppointmentsPage /></RequireRole>} />
            <Route path="schedule" element={<RequireRole roles={['ADVISOR', 'ADMIN']}><DaySchedulePage /></RequireRole>} />
            <Route path="book-for-customer" element={<RequireRole roles={['ADVISOR', 'ADMIN']}><BookForCustomerPage /></RequireRole>} />
            <Route path="not-linked" element={<NotLinkedPage />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </ApiProvider>
  );
}

export function App({ auth, queryClient }: { auth: AuthClient; queryClient: QueryClient }) {
  return (
    <AppProviders auth={auth} queryClient={queryClient}>
      <BrowserRouter>
        <AppContent />
      </BrowserRouter>
    </AppProviders>
  );
}
