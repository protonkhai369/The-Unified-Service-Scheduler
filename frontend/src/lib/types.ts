export type Role = 'CUSTOMER' | 'ADVISOR' | 'ADMIN';

export type Ref = { id: number; name: string };

export type Account = {
  userId: string;
  role: Role | null;
  customer: Ref | null;
  dealership: Ref | null;
};

export type Items<T> = { items: T[] };

export type ServiceType = { id: number; code: string; name: string; durationMinutes: number };

export type Dealership = { id: number; name: string; timeZone: string; opens: string; closes: string };

export type DealershipDetail = Dealership & {
  serviceBays: { id: number; name: string; active: boolean }[];
  technicians: { id: number; name: string; active: boolean; qualifiedServiceTypeIds: number[] }[];
};

export type Vehicle = { id: number; vin: string; make: string; model: string; modelYear: number | null };

export type Customer = { id: number; name: string; email: string; phone: string | null; vehicles: Vehicle[] };

export type Slot = { startTime: string; endTime: string; availableBays: number; availableTechnicians: number };

export type Availability = {
  dealershipId: number;
  serviceTypeId: number;
  date: string;
  timeZone: string;
  durationMinutes: number;
  slots: Slot[];
};

export type Appointment = {
  id: string;
  status: 'CONFIRMED' | 'CANCELLED';
  dealershipId: number;
  customerId: number;
  vehicleId: number;
  serviceTypeId: number;
  serviceBay: { id: number; name: string };
  technician: { id: number; name: string };
  startTime: string;
  endTime: string;
  timeZone: string;
};

export type BookingRequest = {
  dealershipId: number;
  customerId: number;
  vehicleId: number;
  serviceTypeId: number;
  startTime: string;
};
