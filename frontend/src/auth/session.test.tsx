import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, test } from 'vitest';

import { accounts } from '../test/fixtures';
import { fakeAuth, PASSWORD } from '../test/fakeAuth';
import { mockApi, problem } from '../test/handlers';
import { renderApp } from '../test/renderApp';

describe('Sign-in and session', () => {
  test('Successful sign-in', async () => {
    let authorization: string | null = null;
    mockApi(
      accounts.customer,
      http.get('*/api/v1/me', ({ request }) => {
        authorization = request.headers.get('Authorization');
        return HttpResponse.json(accounts.customer);
      }),
    );
    const { user } = renderApp('/sign-in', { auth: fakeAuth({ signedIn: false }) });

    await user.type(await screen.findByLabelText('E-mail'), 'emma.wilson@example.com');
    await user.type(screen.getByLabelText('Password'), PASSWORD);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Book a service' })).toBeInTheDocument();
    expect(authorization).toBe('Bearer test-token');
  });

  test('Wrong credentials', async () => {
    const { user } = renderApp('/sign-in', { auth: fakeAuth({ signedIn: false }) });

    await user.type(await screen.findByLabelText('E-mail'), 'emma.wilson@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail or password is incorrect');
    expect(screen.getByLabelText('E-mail')).toHaveValue('emma.wilson@example.com');
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  test('Signed-out visitor', async () => {
    renderApp('/appointments', { auth: fakeAuth({ signedIn: false }) });

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  test('Expired session', async () => {
    mockApi(accounts.customer, http.get('*/api/v1/me', () => problem(401, 'UNAUTHENTICATED')));
    const auth = fakeAuth();
    renderApp('/book', { auth });

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Your session has expired. Please sign in again.');
    expect(auth.signOut).toHaveBeenCalled();
  });
});
