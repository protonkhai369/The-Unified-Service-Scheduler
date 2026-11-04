import { ApiError } from './api';

const messages: Record<string, string> = {
  NETWORK_ERROR: 'We could not reach the server. Check your connection and try again.',
  NO_AVAILABILITY: 'That time was just taken. Please choose another.',
  VEHICLE_ALREADY_BOOKED: 'This vehicle already has an appointment at that time.',
  SLOT_CONTENTION: 'Many bookings are being made for this day right now. Please try again in a moment.',
  START_TIME_IN_PAST: 'That time has already passed. Please choose a later time.',
  OUTSIDE_OPENING_HOURS: 'That time is outside the dealership’s opening hours.',
  START_TIME_NOT_ON_GRID: 'Please choose one of the offered start times.',
  VEHICLE_NOT_OWNED_BY_CUSTOMER: 'This vehicle does not belong to the selected customer.',
  IDEMPOTENCY_KEY_REUSED: 'This booking changed while it was being sent. Please confirm it again.',
  APPOINTMENT_ALREADY_STARTED: 'This appointment has already started, so it can no longer be cancelled.',
  APPOINTMENT_NOT_FOUND: 'This appointment could not be found.',
  FORBIDDEN: 'Your account is not allowed to do this.',
  VALIDATION_FAILED: 'Some details are missing or invalid.',
  DEALERSHIP_NOT_FOUND: 'This dealership could not be found.',
  SERVICE_TYPE_NOT_FOUND: 'This service could not be found.',
  CUSTOMER_NOT_FOUND: 'This customer could not be found.',
  VEHICLE_NOT_FOUND: 'This vehicle could not be found.',
  UNAUTHENTICATED: 'Your session has expired. Please sign in again.',
};

export type ErrorMessage = { text: string; requestId?: string };

export function errorMessage(error: unknown): ErrorMessage {
  if (error instanceof ApiError && messages[error.code] && error.status !== 500) {
    return { text: messages[error.code] };
  }
  const requestId = error instanceof ApiError ? error.requestId : undefined;
  return { text: 'Something went wrong. Please try again.', requestId };
}
