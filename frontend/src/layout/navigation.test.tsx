import { screen, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';

import { accounts } from '../test/fixtures';
import { mockApi } from '../test/handlers';
import { renderApp } from '../test/renderApp';

function mainNavigation() {
  return screen.getByRole('navigation', { name: 'Main' });
}

describe('Role-based start page and navigation', () => {
  test('Customer start page', async () => {
    mockApi(accounts.customer);
    renderApp('/');

    expect(await screen.findByRole('heading', { name: 'Book a service' })).toBeInTheDocument();
    const links = within(mainNavigation()).getAllByRole('link').map((link) => link.textContent);
    expect(links).toEqual(['Book a service', 'My appointments']);
  });

  test('Advisor start page', async () => {
    mockApi(accounts.advisor);
    renderApp('/');

    expect(await screen.findByRole('heading', { name: 'Day schedule' })).toBeInTheDocument();
    expect(screen.getByText(/Saigon Auto Centre/)).toBeInTheDocument();
    const links = within(mainNavigation()).getAllByRole('link').map((link) => link.textContent);
    expect(links).toEqual(['Day schedule', 'Book for a customer']);
  });

  test('Account without a role', async () => {
    mockApi(accounts.none);
    renderApp('/book');

    expect(await screen.findByRole('heading', { name: 'Your account is not linked yet' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Book a service' })).not.toBeInTheDocument();
  });

  test('marks the current page and lets the person sign out', async () => {
    mockApi(accounts.customer);
    const { user, auth } = renderApp('/appointments');

    expect(await screen.findByRole('heading', { name: 'My appointments' })).toBeInTheDocument();
    expect(within(mainNavigation()).getByRole('link', { name: 'My appointments' })).toHaveAttribute('aria-current', 'page');
    expect(within(mainNavigation()).getByRole('link', { name: 'Book a service' })).not.toHaveAttribute('aria-current');

    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(auth.signOut).toHaveBeenCalled();
  });
});
