import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, beforeEach, vi } from 'vitest';

import { server } from './server';

process.env.TZ = 'UTC';

export const NOW = new Date('2026-11-02T09:00:00Z');

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
  vi.useRealTimers();
});
afterAll(() => server.close());
