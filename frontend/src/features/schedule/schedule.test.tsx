import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, test } from 'vitest';

import type { Appointment } from '../../lib/types';
import { accounts, appointment } from '../../test/fixtures';
import { mockApi } from '../../test/handlers';
import { renderApp } from '../../test/renderApp';

function scheduleHandler(byDate: Record<string, Appointment[]>, requests: string[] = []) {
  return http.get('*/api/v1/dealerships/:id/appointments', ({ params, request }) => {
    const date = new URL(request.url).searchParams.get('date')!;
    requests.push(`${params.id}/${date}`);
    return HttpResponse.json({ items: byDate[date] ?? [] });
  });
}

const day = {
  '2026-11-03': [
    appointment({ id: 'a-1', serviceBay: { id: 4, name: 'Bay A' }, technician: { id: 4, name: 'Nguyen Van An' }, serviceTypeId: 1, startTime: '2026-11-03T08:00:00+07:00', endTime: '2026-11-03T08:45:00+07:00' }),
    appointment({ id: 'a-2', serviceBay: { id: 5, name: 'Bay B' }, technician: { id: 5, name: 'Tran Thi Binh' }, serviceTypeId: 3, startTime: '2026-11-03T10:00:00+07:00', endTime: '2026-11-03T11:30:00+07:00' }),
  ],
};

describe('Day schedule', () => {
  test('Advisor sees the day', async () => {
    mockApi(accounts.advisor, scheduleHandler(day));
    renderApp('/schedule?date=2026-11-03');

    const bayA = await screen.findByRole('region', { name: 'Bay A' });
    expect(await within(bayA).findByText('08:00–08:45')).toBeInTheDocument();
    expect(within(bayA).getByText(/Nguyen Van An/)).toBeInTheDocument();
    expect(within(bayA).getByText(/Oil and filter change/)).toBeInTheDocument();
    const bayB = screen.getByRole('region', { name: 'Bay B' });
    expect(within(bayB).getByText('10:00–11:30')).toBeInTheDocument();
    expect(within(bayB).getByText(/Tran Thi Binh/)).toBeInTheDocument();
    expect(screen.getByText(/Saigon Auto Centre · Tue 3 Nov 2026/)).toBeInTheDocument();
  });

  test('Empty day', async () => {
    mockApi(accounts.advisor, scheduleHandler({}));
    renderApp('/schedule?date=2026-11-03');

    expect(await screen.findByText('No appointments on this day.')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Bay A' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Bay B' })).toBeInTheDocument();
  });

  test('Admin picks a dealership', async () => {
    const requests: string[] = [];
    mockApi(accounts.admin, scheduleHandler(day, requests));
    const { user } = renderApp('/schedule?date=2026-11-03');

    const picker = await screen.findByLabelText('Dealership');
    await user.selectOptions(picker, 'Saigon Auto Centre');

    expect(await screen.findByRole('region', { name: 'Bay A' })).toBeInTheDocument();
    expect(await screen.findByText('08:00–08:45')).toBeInTheDocument();
    expect(requests).toContain('2/2026-11-03');
  });

  test('moves between days, starting from today at the dealership', async () => {
    const requests: string[] = [];
    mockApi(accounts.advisor, scheduleHandler(day, requests));
    const { user } = renderApp('/schedule');

    expect(await screen.findByText(/Saigon Auto Centre · Mon 2 Nov 2026/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next day' }));
    expect(await screen.findByText('08:00–08:45')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Previous day' }));
    await user.click(screen.getByRole('button', { name: 'Previous day' }));
    expect(await screen.findByText(/Sun 1 Nov 2026/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Today' }));
    expect(await screen.findByText(/Mon 2 Nov 2026/)).toBeInTheDocument();
    expect(requests).toEqual(expect.arrayContaining(['2/2026-11-02', '2/2026-11-03', '2/2026-11-01']));
  });
});
