import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { useApi } from '../../lib/ApiProvider';
import { ErrorAlert } from '../../lib/ErrorAlert';
import { formatDate, formatTimeRange, localDateOf } from '../../lib/time';
import type { Appointment, Items, Vehicle } from '../../lib/types';
import { Button, Dialog, EmptyState, LinkButton, Spinner } from '../../ui';
import { vehicleName } from '../booking/BookingFlow';
import { useDealerships, useMyVehicles, useServiceTypes } from '../booking/queries';

export function MyAppointmentsPage() {
  const api = useApi();
  const queryClient = useQueryClient();
  const appointments = useQuery({
    queryKey: ['my-appointments'],
    queryFn: async () => (await api.get<Items<Appointment>>('/api/v1/me/appointments')).items,
  });
  const dealerships = useDealerships();
  const services = useServiceTypes();
  const vehicles = useMyVehicles();
  const [toCancel, setToCancel] = useState<Appointment>();
  const cancel = useMutation({
    mutationFn: (id: string) => api.post<Appointment>(`/api/v1/appointments/${id}/cancel`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['my-appointments'] });
      queryClient.invalidateQueries({ queryKey: ['schedule'] });
      queryClient.invalidateQueries({ queryKey: ['availability'] });
      setToCancel(undefined);
    },
  });

  const describe = (a: Appointment) => ({
    date: formatDate(localDateOf(a.startTime, a.timeZone)),
    time: `${formatTimeRange(a.startTime, a.endTime, a.timeZone)} (${a.timeZone})`,
    where: dealerships.data?.find((d) => d.id === a.dealershipId)?.name ?? `Dealership ${a.dealershipId}`,
    what: services.data?.find((s) => s.id === a.serviceTypeId)?.name ?? `Service ${a.serviceTypeId}`,
    vehicle: vehicles.data?.find((v) => v.id === a.vehicleId),
  });

  const now = Date.now();
  const items = appointments.data ?? [];
  const upcoming = items.filter((a) => a.status === 'CONFIRMED' && Date.parse(a.startTime) > now).sort((a, b) => Date.parse(a.startTime) - Date.parse(b.startTime));
  const past = items.filter((a) => !upcoming.includes(a)).sort((a, b) => Date.parse(b.startTime) - Date.parse(a.startTime));

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <h1 className="text-2xl font-semibold text-navy">My appointments</h1>
        <LinkButton variant="primary" to="/book">
          Book a service
        </LinkButton>
      </div>
      {appointments.isPending && <Spinner label="Loading your appointments" />}
      {appointments.isError && <ErrorAlert error={appointments.error} />}
      {appointments.data && (
        <>
          <section aria-labelledby="upcoming-heading" className="flex flex-col gap-3">
            <h2 id="upcoming-heading" className="text-lg font-semibold text-navy">
              Upcoming
            </h2>
            {upcoming.length === 0 ? (
              <EmptyState title="No upcoming appointments." />
            ) : (
              <ul className="flex flex-col gap-3">
                {upcoming.map((a) => (
                  <Entry key={a.id} appointment={a} details={describe(a)} onCancel={() => { cancel.reset(); setToCancel(a); }} />
                ))}
              </ul>
            )}
          </section>
          <section aria-labelledby="past-heading" className="flex flex-col gap-3">
            <h2 id="past-heading" className="text-lg font-semibold text-navy">
              Past or cancelled
            </h2>
            {past.length === 0 ? (
              <EmptyState title="Nothing here yet." />
            ) : (
              <ul className="flex flex-col gap-3">
                {past.map((a) => (
                  <Entry key={a.id} appointment={a} details={describe(a)} />
                ))}
              </ul>
            )}
          </section>
        </>
      )}
      {toCancel && (
        <Dialog title="Cancel this appointment?" onClose={() => setToCancel(undefined)}>
          <p className="text-ink">
            {describe(toCancel).what} on {describe(toCancel).date}, {describe(toCancel).time}. The bay and technician will be released.
          </p>
          {cancel.isError && <ErrorAlert error={cancel.error} />}
          <div className="flex flex-wrap justify-end gap-3">
            <Button variant="secondary" onClick={() => setToCancel(undefined)}>
              Keep it
            </Button>
            <Button variant="danger" busy={cancel.isPending} onClick={() => cancel.mutate(toCancel.id)}>
              Cancel appointment
            </Button>
          </div>
        </Dialog>
      )}
    </div>
  );
}

type Details = { date: string; time: string; where: string; what: string; vehicle: Vehicle | undefined };

function Entry({ appointment, details, onCancel }: { appointment: Appointment; details: Details; onCancel?: () => void }) {
  const status = appointment.status === 'CANCELLED' ? 'Cancelled' : onCancel ? 'Confirmed' : 'Completed';
  return (
    <li className="flex flex-col gap-3 rounded-lg border border-line p-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex flex-col gap-0.5">
        <p className="font-semibold text-navy">{details.date}</p>
        <p className="text-ink">{details.time}</p>
        <p className="text-slate">
          {details.what} · {details.where}
          {details.vehicle && ` · ${vehicleName(details.vehicle)}`}
        </p>
      </div>
      <div className="flex items-center gap-3">
        <span className={`rounded-full bg-mist px-3 py-1 text-sm font-medium ${appointment.status === 'CANCELLED' ? 'text-slate' : 'text-navy'}`}>{status}</span>
        {onCancel && (
          <Button variant="danger" aria-label={`Cancel appointment on ${details.date}, ${details.time}`} onClick={onCancel}>
            Cancel
          </Button>
        )}
      </div>
    </li>
  );
}
