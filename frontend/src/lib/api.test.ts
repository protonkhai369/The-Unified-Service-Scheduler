import { http, HttpResponse } from 'msw';
import { describe, expect, test, vi } from 'vitest';

import { server } from '../test/server';
import { ApiError, createApiClient } from './api';

function client(token: string | null = 'token-1', onUnauthorized = vi.fn()) {
  return { api: createApiClient({ getAccessToken: async () => token, onUnauthorized }), onUnauthorized };
}

describe('api client', () => {
  test('sends the bearer token and reads JSON', async () => {
    let authorization: string | null = null;
    server.use(
      http.get('*/api/v1/me', ({ request }) => {
        authorization = request.headers.get('Authorization');
        return HttpResponse.json({ role: 'CUSTOMER' });
      }),
    );

    await expect(client().api.get('/api/v1/me')).resolves.toEqual({ role: 'CUSTOMER' });
    expect(authorization).toBe('Bearer token-1');
  });

  test('sends no Authorization header without a session', async () => {
    let authorization: string | null = 'unset';
    server.use(
      http.get('*/api/v1/service-types', ({ request }) => {
        authorization = request.headers.get('Authorization');
        return HttpResponse.json({ items: [] });
      }),
    );

    await client(null).api.get('/api/v1/service-types');
    expect(authorization).toBeNull();
  });

  test('posts JSON with the idempotency key', async () => {
    let body: unknown;
    let key: string | null = null;
    server.use(
      http.post('*/api/v1/appointments', async ({ request }) => {
        body = await request.json();
        key = request.headers.get('Idempotency-Key');
        return HttpResponse.json({ id: 'a-1' }, { status: 201 });
      }),
    );

    await client().api.post('/api/v1/appointments', { vehicleId: 1 }, { idempotencyKey: 'key-1' });
    expect(body).toEqual({ vehicleId: 1 });
    expect(key).toBe('key-1');
  });

  test('turns a problem into an ApiError', async () => {
    server.use(
      http.post('*/api/v1/appointments', () =>
        HttpResponse.json(
          { status: 409, code: 'NO_AVAILABILITY', detail: 'No bay is free.', requestId: 'req-1', errors: [] },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const error = await client().api.post('/api/v1/appointments', {}).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, code: 'NO_AVAILABILITY', detail: 'No bay is free.', requestId: 'req-1' });
  });

  test('a body that is not a problem becomes INTERNAL_ERROR with the request id header', async () => {
    server.use(http.get('*/api/v1/me', () => new HttpResponse('Bad gateway', { status: 502, headers: { 'X-Request-Id': 'req-502' } })));

    const error = await client().api.get('/api/v1/me').catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 502, code: 'INTERNAL_ERROR', requestId: 'req-502' });
  });

  test('a 401 signs out before failing', async () => {
    server.use(
      http.get('*/api/v1/me', () =>
        HttpResponse.json({ status: 401, code: 'UNAUTHENTICATED' }, { status: 401, headers: { 'Content-Type': 'application/problem+json' } }),
      ),
    );
    const { api, onUnauthorized } = client();

    const error = await api.get('/api/v1/me').catch((e: unknown) => e);
    expect(onUnauthorized).toHaveBeenCalledOnce();
    expect(error).toMatchObject({ status: 401, code: 'UNAUTHENTICATED' });
  });

  test('a request without a response becomes NETWORK_ERROR', async () => {
    server.use(http.post('*/api/v1/appointments', () => HttpResponse.error()));

    const error = await client().api.post('/api/v1/appointments', {}).catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });
});
