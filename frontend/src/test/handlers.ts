import { http, HttpResponse, type RequestHandler } from 'msw';

import type { Account } from '../lib/types';
import { accounts, corolla, golf, london, saigon, saigonDetail, serviceTypes } from './fixtures';
import { server } from './server';

export function problem(status: number, code: string, requestId = `req-${code.toLowerCase()}`) {
  return HttpResponse.json(
    { type: 'about:blank', status, code, detail: code, requestId },
    { status, headers: { 'Content-Type': 'application/problem+json', 'X-Request-Id': requestId } },
  );
}

export function catalogHandlers() {
  return [
    http.get('*/api/v1/service-types', () => HttpResponse.json({ items: serviceTypes })),
    http.get('*/api/v1/dealerships', () => HttpResponse.json({ items: [london, saigon] })),
    http.get('*/api/v1/dealerships/:id', ({ params }) =>
      Number(params.id) === saigon.id ? HttpResponse.json(saigonDetail) : HttpResponse.json({ ...london, serviceBays: [{ id: 1, name: 'Bay 1', active: true }], technicians: [] }),
    ),
  ];
}

export function accountHandlers(account: Account = accounts.customer) {
  return [
    http.get('*/api/v1/me', () => HttpResponse.json(account)),
    http.get('*/api/v1/me/vehicles', () => HttpResponse.json({ items: [golf, corolla] })),
    http.get('*/api/v1/me/appointments', () => HttpResponse.json({ items: [] })),
  ];
}

export function mockApi(account: Account = accounts.customer, ...overrides: RequestHandler[]) {
  server.use(...catalogHandlers(), ...accountHandlers(account));
  if (overrides.length) {
    server.use(...overrides);
  }
}
