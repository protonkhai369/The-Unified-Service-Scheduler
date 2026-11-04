import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, test } from 'vitest';

import type { Appointment } from '../../lib/types';
import { accounts, appointment } from '../../test/fixtures';
import { mockApi, problem } from '../../test/handlers';
import { renderApp } from '../../test/renderApp';

const upcoming = appointment({ id: 'a-up', startTime: '2026-11-03T10:00:00+07:00', endTime: '2026-11-03T10:45:00+07:00' });
const cancelled = appointment({ id: 'a-old', status: 'CANCELLED', startTime: '2026-11-04T08:00:00+07:00', endTime: '2026-11-04T08:45:00+07:00' });

function appointmentsHandlers(list: Appointment[], cancelResponse?: () => Response) {
  let cancels = 0;
  return {
    cancels: () => cancels,
    handlers: [
      http.get('*/api/v1/me/appointments', () => HttpResponse.json({ items: list })),
      http.post('*/api/v1/appointments/:id/cancel', ({ params }) => {
        cancels += 1;
        if (cancelResponse) {
          return cancelResponse();
        }
        const target = list.find((a) => a.id === params.id)!;
        target.status = 'CANCELLED';
        return HttpResponse.json(target);
      }),
    ],
  };
}

describe('My appointments', () => {
  test('Upcoming and past', async () => {
    const { handlers } = appointmentsHandlers([structuredClone(upcoming), structuredClone(cancelled)]);
    mockApi(accounts.customer, ...handlers);
    renderApp('/appointments');

    const upcomingSection = await screen.findByRole('region', { name: 'Upcoming' });
    const pastSection = screen.getByRole('region', { name: 'Past or cancelled' });
    expect(within(upcomingSection).getByText('Tue 3 Nov 2026')).toBeInTheDocument();
    expect(within(upcomingSection).getByText(/10:00–10:45 \(Asia\/Ho_Chi_Minh\)/)).toBeInTheDocument();
    expect(within(upcomingSection).getByText(/Saigon Auto Centre/)).toBeInTheDocument();
    expect(within(upcomingSection).getByText(/Oil and filter change/)).toBeInTheDocument();
    expect(within(upcomingSection).getByRole('button', { name: /Cancel/ })).toBeInTheDocument();
    expect(within(pastSection).getByText('Cancelled')).toBeInTheDocument();
    expect(within(pastSection).queryByRole('button', { name: /Cancel/ })).not.toBeInTheDocument();
  });

  test('Cancel with confirmation', async () => {
    const api = appointmentsHandlers([structuredClone(upcoming)]);
    mockApi(accounts.customer, ...api.handlers);
    const { user } = renderApp('/appointments');

    await user.click(await within(await screen.findByRole('region', { name: 'Upcoming' })).findByRole('button', { name: /Cancel/ }));
    const dialog = screen.getByRole('dialog', { name: 'Cancel this appointment?' });
    await user.click(within(dialog).getByRole('button', { name: 'Cancel appointment' }));

    const pastSection = await screen.findByRole('region', { name: 'Past or cancelled' });
    expect(await within(pastSection).findByText('Cancelled')).toBeInTheDocument();
    expect(api.cancels()).toBe(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  test('Too late to cancel', async () => {
    const api = appointmentsHandlers([structuredClone(upcoming)], () => problem(409, 'APPOINTMENT_ALREADY_STARTED'));
    mockApi(accounts.customer, ...api.handlers);
    const { user } = renderApp('/appointments');

    await user.click(await within(await screen.findByRole('region', { name: 'Upcoming' })).findByRole('button', { name: /Cancel/ }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel appointment' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('This appointment has already started, so it can no longer be cancelled.');
  });
});
