import { describe, expect, test } from 'vitest';

import { addDays, formatDate, formatTime, formatTimeRange, minutesSince, todayIn } from './time';

describe('dealership time', () => {
  test('runs in UTC, so a browser-zone bug would show', () => {
    expect(new Date('2026-11-03T10:00:00+07:00').getHours()).toBe(3);
  });

  test('formats a start in the dealership zone, never the browser zone', () => {
    expect(formatTime('2026-11-03T10:00:00+07:00', 'Asia/Ho_Chi_Minh')).toBe('10:00');
    expect(formatTime('2026-11-03T10:00:00+07:00', 'Europe/London')).toBe('03:00');
  });

  test('formats a range with an en dash', () => {
    expect(formatTimeRange('2026-11-03T10:00:00+07:00', '2026-11-03T10:45:00+07:00', 'Asia/Ho_Chi_Minh')).toBe('10:00–10:45');
  });

  test('today is the dealership’s local date', () => {
    const now = new Date('2026-11-02T18:00:00Z');

    expect(todayIn('Asia/Ho_Chi_Minh', now)).toBe('2026-11-03');
    expect(todayIn('Europe/London', now)).toBe('2026-11-02');
  });

  test('adds days to a local date across month ends and daylight saving', () => {
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01');
    expect(addDays('2026-03-29', -1)).toBe('2026-03-28');
  });

  test('formats a local date for people', () => {
    expect(formatDate('2026-11-03')).toBe('Tue 3 Nov 2026');
  });

  test('counts minutes since opening on the dealership clock', () => {
    expect(minutesSince('2026-11-03T08:00:00+07:00', '07:30', 'Asia/Ho_Chi_Minh')).toBe(30);
    expect(minutesSince('2026-03-29T09:00:00+01:00', '08:00', 'Europe/London')).toBe(60);
  });
});
