import type { ReactNode } from 'react';
import { Navigate } from 'react-router';

import type { Role } from '../lib/types';
import { useAccount, usableRole } from './MeProvider';

export function StartRedirect() {
  const role = usableRole(useAccount());
  if (role === 'CUSTOMER') {
    return <Navigate to="/book" replace />;
  }
  return <Navigate to={role ? '/schedule' : '/not-linked'} replace />;
}

export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const role = usableRole(useAccount());
  return role && roles.includes(role) ? children : <Navigate to="/" replace />;
}

export function NotLinkedPage() {
  const account = useAccount();
  if (usableRole(account)) {
    return <Navigate to="/" replace />;
  }
  return (
    <section className="flex flex-col gap-3">
      <h1 className="text-2xl font-semibold text-navy">Your account is not linked yet</h1>
      <p className="max-w-prose text-slate">
        This account is signed in but is not linked to a customer record or a dealership, so it cannot book or view
        appointments. Please ask a dealership administrator to link it.
      </p>
    </section>
  );
}
