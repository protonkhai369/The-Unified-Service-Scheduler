import { screen, waitFor, within } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, test } from 'vitest';

import type { BookingRequest, ServiceType } from '../../lib/types';
import { accounts, appointment, availability, serviceTypes } from '../../test/fixtures';
import { mockApi, problem } from '../../test/handlers';
import { renderApp } from '../../test/renderApp';

function availabilityHandler(starts: string[], calls?: { count: number }) {
  return http.get('*/api/v1/dealerships/:id/availability', ({ request }) => {
    if (calls) {
      calls.count += 1;
    }
    const date = new URL(request.url).searchParams.get('date')!;
    return HttpResponse.json(availability(date, starts));
  });
}

async function chooseUpToTime(user: UserEvent) {
  await user.click(await screen.findByRole('button', { name: /Volkswagen Golf/ }));
  await user.click(screen.getByRole('button', { name: 'Continue' }));
  await user.click(await screen.findByRole('button', { name: /Oil and filter change/ }));
  await user.click(screen.getByRole('button', { name: 'Continue' }));
  await user.selectOptions(await screen.findByLabelText('Dealership'), 'Saigon Auto Centre');
  const date = screen.getByLabelText('Date');
  await user.clear(date);
  await user.type(date, '2026-11-03');
  await user.click(screen.getByRole('button', { name: 'Continue' }));
}

async function chooseUpToConfirm(user: UserEvent) {
  await chooseUpToTime(user);
  await user.click(await screen.findByRole('button', { name: /^10:00/ }));
  await user.click(screen.getByRole('button', { name: 'Continue' }));
  await screen.findByRole('heading', { name: 'Check and confirm' });
}

describe('Booking a service', () => {
  test('Booking happy path', async () => {
    const requests: BookingRequest[] = [];
    mockApi(
      accounts.customer,
      availabilityHandler(['10:00', '10:30']),
      http.post('*/api/v1/appointments', async ({ request }) => {
        requests.push((await request.json()) as BookingRequest);
        return HttpResponse.json(appointment(), { status: 201 });
      }),
    );
    const { user } = renderApp('/book');

    await chooseUpToConfirm(user);
    await user.click(screen.getByRole('button', { name: 'Confirm booking' }));

    expect(await screen.findByRole('heading', { name: 'Your service is booked' })).toBeInTheDocument();
    expect(requests).toEqual([{ dealershipId: 2, customerId: 1, vehicleId: 1, serviceTypeId: 1, startTime: '2026-11-03T10:00:00+07:00' }]);
    const summary = screen.getByRole('region', { name: 'Booking summary' });
    expect(summary).toHaveTextContent('Bay A');
    expect(summary).toHaveTextContent('Nguyen Van An');
    expect(summary).toHaveTextContent('10:00–10:45 (Asia/Ho_Chi_Minh)');
  });

  test("Times in the dealership's zone", async () => {
    mockApi(accounts.customer, availabilityHandler(['10:00']));
    const { user } = renderApp('/book');

    await chooseUpToTime(user);

    expect(await screen.findByRole('button', { name: /^10:00/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^03:00/ })).not.toBeInTheDocument();
    expect(screen.getByText(/Asia\/Ho_Chi_Minh/)).toBeInTheDocument();
  });

  test('No free slot that day', async () => {
    mockApi(accounts.customer, availabilityHandler([]));
    const { user } = renderApp('/book');

    await chooseUpToTime(user);

    expect(await screen.findByText('No time is free on this day.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Choose another date' }));
    expect(screen.getByRole('heading', { name: 'Choose a dealership and date' })).toBeInTheDocument();
  });

  test('Double click books once', async () => {
    let posts = 0;
    mockApi(
      accounts.customer,
      availabilityHandler(['10:00']),
      http.post('*/api/v1/appointments', async () => {
        posts += 1;
        await delay(50);
        return HttpResponse.json(appointment(), { status: 201 });
      }),
    );
    const { user } = renderApp('/book');

    await chooseUpToConfirm(user);
    await user.dblClick(screen.getByRole('button', { name: 'Confirm booking' }));

    expect(await screen.findByRole('heading', { name: 'Your service is booked' })).toBeInTheDocument();
    expect(posts).toBe(1);
  });

  test('Retry after a network failure', async () => {
    const keys: (string | null)[] = [];
    mockApi(
      accounts.customer,
      availabilityHandler(['10:00']),
      http.post('*/api/v1/appointments', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'));
        return keys.length === 1 ? HttpResponse.error() : HttpResponse.json(appointment(), { status: 201 });
      }),
    );
    const { user } = renderApp('/book');

    await chooseUpToConfirm(user);
    await user.click(screen.getByRole('button', { name: 'Confirm booking' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('We could not reach the server');
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('heading', { name: 'Your service is booked' })).toBeInTheDocument();
    expect(keys).toHaveLength(2);
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(keys[1]).toBe(keys[0]);
  });

  test('Slot taken meanwhile', async () => {
    const calls = { count: 0 };
    mockApi(accounts.customer, availabilityHandler(['10:00', '10:30'], calls), http.post('*/api/v1/appointments', () => problem(409, 'NO_AVAILABILITY')));
    const { user } = renderApp('/book');

    await chooseUpToConfirm(user);
    const before = calls.count;
    await user.click(screen.getByRole('button', { name: 'Confirm booking' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('That time was just taken. Please choose another.');
    expect(screen.getByRole('heading', { name: 'Choose a time' })).toBeInTheDocument();
    await waitFor(() => expect(calls.count).toBeGreaterThan(before));
  });

  test('Vehicle already booked', async () => {
    mockApi(accounts.customer, availabilityHandler(['10:00']), http.post('*/api/v1/appointments', () => problem(409, 'VEHICLE_ALREADY_BOOKED')));
    const { user } = renderApp('/book');

    await chooseUpToConfirm(user);
    await user.click(screen.getByRole('button', { name: 'Confirm booking' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('This vehicle already has an appointment at that time.');
  });
});

describe('Choosing a service type', () => {
  async function toServiceStep(user: UserEvent) {
    await user.click(await screen.findByRole('button', { name: /Volkswagen Golf/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await screen.findByRole('heading', { name: 'Choose a service' });
  }

  test('Few services as options', async () => {
    mockApi(accounts.customer);
    const { user } = renderApp('/book');

    await toServiceStep(user);

    const options = within(screen.getByRole('group', { name: 'Services' })).getAllByRole('button');
    expect(options).toHaveLength(4);
    expect(options[0]).toHaveTextContent('Oil and filter change');
    expect(options[0]).toHaveTextContent('45 min');
    expect(screen.queryByLabelText('Filter services')).not.toBeInTheDocument();
  });

  test('Filtering ignores accents', async () => {
    const many: ServiceType[] = [
      ...serviceTypes,
      { id: 5, code: 'BRAKE_CHECK', name: 'Kiểm tra phanh', durationMinutes: 30 },
      { id: 6, code: 'AC_SERVICE', name: 'Air conditioning service', durationMinutes: 60 },
      { id: 7, code: 'BATTERY', name: 'Battery test', durationMinutes: 15 },
    ];
    mockApi(accounts.customer, http.get('*/api/v1/service-types', () => HttpResponse.json({ items: many })));
    const { user } = renderApp('/book');

    await toServiceStep(user);
    await user.type(screen.getByLabelText('Filter services'), 'kiem tra');

    const options = within(screen.getByRole('group', { name: 'Services' })).getAllByRole('button');
    expect(options.map((option) => option.querySelector('span')?.textContent)).toEqual(['Kiểm tra phanh']);
  });
});
