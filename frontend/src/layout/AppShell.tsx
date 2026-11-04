import { CalendarCheck, LogOut } from 'lucide-react';
import { NavLink, Outlet } from 'react-router';

import { useAccount, usableRole } from '../account/MeProvider';
import { useAuth } from '../auth/AuthProvider';
import type { Account, Role } from '../lib/types';
import { Button } from '../ui';

const links: Record<Role, { to: string; label: string }[]> = {
  CUSTOMER: [
    { to: '/book', label: 'Book a service' },
    { to: '/appointments', label: 'My appointments' },
  ],
  ADVISOR: [
    { to: '/schedule', label: 'Day schedule' },
    { to: '/book-for-customer', label: 'Book for a customer' },
  ],
  ADMIN: [
    { to: '/schedule', label: 'Day schedule' },
    { to: '/book-for-customer', label: 'Book for a customer' },
  ],
};

function signedInAs(account: Account, role: Role | null): string {
  switch (role) {
    case 'CUSTOMER':
      return account.customer!.name;
    case 'ADVISOR':
      return `Advisor · ${account.dealership!.name}`;
    case 'ADMIN':
      return 'Administrator';
    default:
      return 'Account not linked';
  }
}

export function AppShell() {
  const account = useAccount();
  const role = usableRole(account);
  const { session, endSession } = useAuth();

  return (
    <div className="min-h-dvh bg-white">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-lg focus:bg-yellow focus:px-4 focus:py-2 focus:text-ink">
        Skip to content
      </a>
      <header className="on-navy bg-navy text-white">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-8 gap-y-1 px-4 py-2">
          <p className="flex items-center gap-2 py-2 font-semibold">
            <CalendarCheck aria-hidden="true" className="size-5 text-yellow" />
            Service Scheduler
          </p>
          {role && (
            <nav aria-label="Main" className="order-3 w-full sm:order-none sm:w-auto">
              <ul className="flex gap-6">
                {links[role].map((link) => (
                  <li key={link.to}>
                    <NavLink
                      to={link.to}
                      className={({ isActive }) =>
                        `inline-flex min-h-11 items-center border-b-2 font-medium transition-colors duration-150 ${
                          isActive ? 'border-yellow text-yellow' : 'border-transparent text-white hover:text-yellow'
                        }`
                      }
                    >
                      {link.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </nav>
          )}
          <div className="ml-auto flex items-center gap-2 text-sm">
            <span className="hidden text-right sm:block">
              <span className="block font-medium">{signedInAs(account, role)}</span>
              <span className="block text-white/80">{session?.email}</span>
            </span>
            <Button variant="ghost" onClick={() => endSession()}>
              <LogOut aria-hidden="true" className="size-4" />
              Sign out
            </Button>
          </div>
        </div>
      </header>
      <main id="main" tabIndex={-1} className="mx-auto max-w-5xl px-4 py-8 focus:outline-none">
        <Outlet />
      </main>
    </div>
  );
}
