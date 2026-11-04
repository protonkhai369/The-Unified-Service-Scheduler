import type { Account, Appointment, Availability, Customer, Dealership, DealershipDetail, ServiceType, Vehicle } from '../lib/types';

export const london: Dealership = { id: 1, name: 'Riverside Motors London', timeZone: 'Europe/London', opens: '08:00', closes: '18:00' };
export const saigon: Dealership = { id: 2, name: 'Saigon Auto Centre', timeZone: 'Asia/Ho_Chi_Minh', opens: '07:30', closes: '17:30' };

export const saigonDetail: DealershipDetail = {
  ...saigon,
  serviceBays: [
    { id: 4, name: 'Bay A', active: true },
    { id: 5, name: 'Bay B', active: true },
  ],
  technicians: [
    { id: 4, name: 'Nguyen Van An', active: true, qualifiedServiceTypeIds: [1, 2, 3, 4] },
    { id: 5, name: 'Tran Thi Binh', active: true, qualifiedServiceTypeIds: [1, 2] },
  ],
};

export const serviceTypes: ServiceType[] = [
  { id: 1, code: 'OIL_CHANGE', name: 'Oil and filter change', durationMinutes: 45 },
  { id: 2, code: 'TYRE_ROTATION', name: 'Tyre rotation and balancing', durationMinutes: 30 },
  { id: 3, code: 'BRAKE_SERVICE', name: 'Brake inspection and pad replacement', durationMinutes: 90 },
  { id: 4, code: 'FULL_SERVICE', name: 'Full annual service', durationMinutes: 180 },
];

export const golf: Vehicle = { id: 1, vin: 'WVWZZZ1KZ8W000001', make: 'Volkswagen', model: 'Golf', modelYear: 2019 };
export const corolla: Vehicle = { id: 2, vin: 'JTDBR32E720000002', make: 'Toyota', model: 'Corolla', modelYear: 2021 };
export const focus: Vehicle = { id: 3, vin: 'WF0AXXGCDA0000003', make: 'Ford', model: 'Focus', modelYear: 2018 };

export const accounts: Record<'customer' | 'advisor' | 'admin' | 'none', Account> = {
  customer: { userId: 'u-1', role: 'CUSTOMER', customer: { id: 1, name: 'Emma Wilson' }, dealership: null },
  advisor: { userId: 'u-2', role: 'ADVISOR', customer: null, dealership: { id: 2, name: 'Saigon Auto Centre' } },
  admin: { userId: 'u-3', role: 'ADMIN', customer: null, dealership: null },
  none: { userId: 'u-4', role: null, customer: null, dealership: null },
};

export const duc: Customer = { id: 2, name: 'Lê Minh Đức', email: 'minh.duc@example.com', phone: null, vehicles: [focus] };

export function availability(date: string, starts: string[], durationMinutes = 45): Availability {
  return {
    dealershipId: saigon.id,
    serviceTypeId: 1,
    date,
    timeZone: saigon.timeZone,
    durationMinutes,
    slots: starts.map((time) => {
      const start = new Date(`${date}T${time}:00+07:00`);
      const end = new Date(start.getTime() + durationMinutes * 60_000);
      return {
        startTime: `${date}T${time}:00+07:00`,
        endTime: toSaigonOffset(end),
        availableBays: 2,
        availableTechnicians: 2,
      };
    }),
  };
}

function toSaigonOffset(instant: Date): string {
  const local = new Date(instant.getTime() + 7 * 3_600_000).toISOString().slice(0, 19);
  return `${local}+07:00`;
}

export function appointment(overrides: Partial<Appointment> = {}): Appointment {
  return {
    id: 'a-1',
    status: 'CONFIRMED',
    dealershipId: saigon.id,
    customerId: 1,
    vehicleId: golf.id,
    serviceTypeId: 1,
    serviceBay: { id: 4, name: 'Bay A' },
    technician: { id: 4, name: 'Nguyen Van An' },
    startTime: '2026-11-03T10:00:00+07:00',
    endTime: '2026-11-03T10:45:00+07:00',
    timeZone: saigon.timeZone,
    ...overrides,
  };
}
