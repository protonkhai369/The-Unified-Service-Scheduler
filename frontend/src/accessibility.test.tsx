import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { screen } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, test } from 'vitest';

import { accounts, appointment, availability } from './test/fixtures';
import { mockApi, problem } from './test/handlers';
import { renderApp } from './test/renderApp';

async function tabTo(user: UserEvent, target: HTMLElement) {
  for (let presses = 0; presses < 40 && document.activeElement !== target; presses++) {
    await user.tab();
  }
  expect(target).toHaveFocus();
}

function scheduleFails(response: () => Response) {
  return http.get('*/api/v1/dealerships/:id/appointments', response);
}

describe('Accessible, minimalist presentation', () => {
  test('Keyboard-only booking', async () => {
    mockApi(
      accounts.customer,
      http.get('*/api/v1/dealerships/:id/availability', ({ request }) =>
        HttpResponse.json(availability(new URL(request.url).searchParams.get('date')!, ['10:00'])),
      ),
      http.post('*/api/v1/appointments', () => HttpResponse.json(appointment(), { status: 201 })),
    );
    const { user } = renderApp('/book');

    const golf = await screen.findByRole('button', { name: /Volkswagen Golf/ });
    await tabTo(user, golf);
    await user.keyboard(' ');
    expect(golf).toHaveAttribute('aria-pressed', 'true');
    await tabTo(user, screen.getByRole('button', { name: 'Continue' }));
    await user.keyboard('{Enter}');

    expect(await screen.findByRole('heading', { name: 'Choose a service' })).toHaveFocus();
    await tabTo(user, screen.getByRole('button', { name: /Oil and filter change/ }));
    await user.keyboard('{Enter}');
    await tabTo(user, screen.getByRole('button', { name: 'Continue' }));
    await user.keyboard('{Enter}');

    const dealership = await screen.findByLabelText('Dealership');
    await tabTo(user, dealership);
    await user.selectOptions(dealership, 'Saigon Auto Centre');
    await tabTo(user, screen.getByRole('button', { name: 'Continue' }));
    await user.keyboard('{Enter}');

    await tabTo(user, await screen.findByRole('button', { name: /^10:00/ }));
    await user.keyboard(' ');
    await tabTo(user, screen.getByRole('button', { name: 'Continue' }));
    await user.keyboard('{Enter}');

    await tabTo(user, await screen.findByRole('button', { name: 'Confirm booking' }));
    await user.keyboard('{Enter}');

    expect(await screen.findByRole('heading', { name: 'Your service is booked' })).toBeInTheDocument();
  });

  test('every focused control shows the focus ring', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/styles/index.css'), 'utf-8');

    expect(css).toMatch(/:focus-visible\s*\{\s*outline:\s*2px solid var\(--color-ocean\)/);
    expect(css).toMatch(/\.on-navy :focus-visible\s*\{\s*outline-color:\s*var\(--color-yellow\)/);
  });

  test('Errors are announced', async () => {
    mockApi(accounts.advisor, scheduleFails(() => problem(403, 'FORBIDDEN')));
    renderApp('/schedule?date=2026-11-03');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Your account is not allowed to do this.');
    expect(alert).not.toHaveTextContent('FORBIDDEN');
  });

  test.each([
    ['a 500', () => problem(500, 'INTERNAL_ERROR', 'req-500')],
    ['an unknown code', () => problem(422, 'BRAND_NEW_RULE', 'req-500')],
    ['a body that is not a problem', () => new HttpResponse('<html>Bad gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html', 'X-Request-Id': 'req-500' } })],
  ])('Unknown error: %s', async (_case, response) => {
    mockApi(accounts.advisor, scheduleFails(response));
    renderApp('/schedule?date=2026-11-03');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong. Please try again.');
    expect(alert).toHaveTextContent('Reference for support: req-500');
  });
});
