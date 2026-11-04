import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, test } from 'vitest';

import type { BookingRequest, Customer } from '../../lib/types';
import { accounts, appointment, availability, duc } from '../../test/fixtures';
import { mockApi } from '../../test/handlers';
import { renderApp } from '../../test/renderApp';

function customersHandler(found: Customer[], queries: string[]) {
  return http.get('*/api/v1/customers', ({ request }) => {
    queries.push(new URL(request.url).searchParams.get('q')!);
    return HttpResponse.json({ items: found });
  });
}

describe('Booking for a customer', () => {
  test('Find and book for a customer', async () => {
    const queries: string[] = [];
    const requests: BookingRequest[] = [];
    mockApi(
      accounts.advisor,
      customersHandler([duc], queries),
      http.get('*/api/v1/dealerships/:id/availability', ({ request }) =>
        HttpResponse.json(availability(new URL(request.url).searchParams.get('date')!, ['10:00'])),
      ),
      http.post('*/api/v1/appointments', async ({ request }) => {
        requests.push((await request.json()) as BookingRequest);
        return HttpResponse.json(appointment({ customerId: duc.id, vehicleId: 3 }), { status: 201 });
      }),
    );
    const { user } = renderApp('/book-for-customer');

    await user.type(await screen.findByLabelText('Find a customer'), 'duc');
    const result = await screen.findByRole('group', { name: 'Lê Minh Đức' });
    expect(result).toHaveTextContent('minh.duc@example.com');
    await user.click(within(result).getByRole('button', { name: /Ford Focus/ }));

    expect(await screen.findByRole('heading', { name: 'Choose a service' })).toBeInTheDocument();
    expect(screen.getByText(/Booking for Lê Minh Đức/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Oil and filter change/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.selectOptions(await screen.findByLabelText('Dealership'), 'Saigon Auto Centre');
    const date = screen.getByLabelText('Date');
    await user.clear(date);
    await user.type(date, '2026-11-03');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(await screen.findByRole('button', { name: /^10:00/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm booking' }));

    expect(await screen.findByRole('heading', { name: 'Your service is booked' })).toBeInTheDocument();
    expect(requests).toEqual([{ dealershipId: 2, customerId: 2, vehicleId: 3, serviceTypeId: 1, startTime: '2026-11-03T10:00:00+07:00' }]);
    expect(queries).toEqual(['duc']);
  });

  test('Too short to search', async () => {
    const queries: string[] = [];
    mockApi(accounts.advisor, customersHandler([duc], queries));
    const { user } = renderApp('/book-for-customer');

    await user.type(await screen.findByLabelText('Find a customer'), 'd');
    await new Promise((resolve) => setTimeout(resolve, 500));

    expect(queries).toEqual([]);
    expect(screen.getByText(/at least two characters/)).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Lê Minh Đức' })).not.toBeInTheDocument();
  });

  test('Nobody found', async () => {
    const queries: string[] = [];
    mockApi(accounts.advisor, customersHandler([], queries));
    const { user } = renderApp('/book-for-customer');

    await user.type(await screen.findByLabelText('Find a customer'), 'zzzz');

    expect(await screen.findByText(/No customer matches/)).toBeInTheDocument();
    expect(queries).toEqual(['zzzz']);
  });
});
