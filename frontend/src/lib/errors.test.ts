import { describe, expect, test } from 'vitest';

import { ApiError } from './api';
import { errorMessage } from './errors';

const GENERIC = 'Something went wrong. Please try again.';

const businessCodes = [
  'VALIDATION_FAILED',
  'UNAUTHENTICATED',
  'FORBIDDEN',
  'DEALERSHIP_NOT_FOUND',
  'SERVICE_TYPE_NOT_FOUND',
  'CUSTOMER_NOT_FOUND',
  'VEHICLE_NOT_FOUND',
  'APPOINTMENT_NOT_FOUND',
  'VEHICLE_NOT_OWNED_BY_CUSTOMER',
  'START_TIME_IN_PAST',
  'OUTSIDE_OPENING_HOURS',
  'START_TIME_NOT_ON_GRID',
  'IDEMPOTENCY_KEY_REUSED',
  'NO_AVAILABILITY',
  'SLOT_CONTENTION',
  'VEHICLE_ALREADY_BOOKED',
  'APPOINTMENT_ALREADY_STARTED',
  'NETWORK_ERROR',
];

describe('error messages', () => {
  test.each(businessCodes)('%s has its own sentence', (code) => {
    const { text, requestId } = errorMessage(new ApiError(409, code, undefined, 'req-1', []));

    expect(text).not.toBe(GENERIC);
    expect(text).not.toContain(code);
    expect(requestId).toBeUndefined();
  });

  test.each(['NOT_FOUND', 'METHOD_NOT_ALLOWED', 'UNSUPPORTED_MEDIA_TYPE', 'REQUEST_REJECTED', 'INTERNAL_ERROR'])(
    '%s means a bug or an outage: generic sentence with the request id',
    (code) => {
      expect(errorMessage(new ApiError(400, code, undefined, 'req-1', []))).toEqual({ text: GENERIC, requestId: 'req-1' });
    },
  );
});
