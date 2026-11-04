import { useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { CSSProperties } from 'react';
import { useSearchParams } from 'react-router';

import { useAccount, usableRole } from '../../account/MeProvider';
import { useApi } from '../../lib/ApiProvider';
import { ErrorAlert } from '../../lib/ErrorAlert';
import { addDays, formatDate, formatTimeRange, minutesSince, todayIn } from '../../lib/time';
import type { Appointment, DealershipDetail, Items } from '../../lib/types';
import { Button, EmptyState, SelectField, Spinner } from '../../ui';
import { useDealerships, useServiceTypes } from '../booking/queries';

function minutesOf(time: string): number {
  const [hours, minutes] = time.split(':').map(Number);
  return hours * 60 + minutes;
}

export function DaySchedulePage() {
  const api = useApi();
  const account = useAccount();
  const isAdmin = usableRole(account) === 'ADMIN';
  const [params, setParams] = useSearchParams();
  const dealerships = useDealerships();
  const services = useServiceTypes();

  const dealershipId = isAdmin ? Number(params.get('dealershipId')) || dealerships.data?.[0]?.id : account.dealership!.id;
  const detail = useQuery({
    queryKey: ['dealership', dealershipId],
    queryFn: () => api.get<DealershipDetail>(`/api/v1/dealerships/${dealershipId}`),
    enabled: dealershipId !== undefined,
    staleTime: 5 * 60_000,
  });
  const zone = detail.data?.timeZone;
  const date = params.get('date') ?? (zone ? todayIn(zone) : '');
  const schedule = useQuery({
    queryKey: ['schedule', dealershipId, date],
    queryFn: async () => (await api.get<Items<Appointment>>(`/api/v1/dealerships/${dealershipId}/appointments?date=${date}`)).items,
    enabled: dealershipId !== undefined && date !== '',
  });

  function update(changes: Record<string, string | null>) {
    setParams((current) => {
      const next = new URLSearchParams(current);
      Object.entries(changes).forEach(([key, value]) => (value === null ? next.delete(key) : next.set(key, value)));
      return next;
    });
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-4">
        <h1 className="text-2xl font-semibold text-navy">Day schedule</h1>
        {isAdmin && dealerships.data && (
          <div className="max-w-sm">
            <SelectField label="Dealership" value={dealershipId ?? ''} onChange={(e) => update({ dealershipId: e.target.value })}>
              {dealerships.data.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </SelectField>
          </div>
        )}
        {detail.data && date && (
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-slate">
              {detail.data.name} · {formatDate(date)} · times in {detail.data.timeZone}
            </p>
            <div className="flex gap-2">
              <Button variant="secondary" aria-label="Previous day" onClick={() => update({ date: addDays(date, -1) })}>
                <ChevronLeft aria-hidden="true" className="size-4" />
              </Button>
              <Button variant="secondary" onClick={() => update({ date: null })}>
                Today
              </Button>
              <Button variant="secondary" aria-label="Next day" onClick={() => update({ date: addDays(date, 1) })}>
                <ChevronRight aria-hidden="true" className="size-4" />
              </Button>
            </div>
          </div>
        )}
      </div>

      {(detail.isPending || schedule.isPending) && <Spinner label="Loading the schedule" />}
      {detail.isError && <ErrorAlert error={detail.error} />}
      {schedule.isError && <ErrorAlert error={schedule.error} />}

      {detail.data && schedule.data && (
        <Timeline dealership={detail.data} appointments={schedule.data} serviceName={(id) => services.data?.find((s) => s.id === id)?.name ?? `Service ${id}`} />
      )}
    </div>
  );
}

function Timeline({ dealership, appointments, serviceName }: { dealership: DealershipDetail; appointments: Appointment[]; serviceName: (id: number) => string }) {
  const open = minutesOf(dealership.opens);
  const span = minutesOf(dealership.closes) - open;
  const hours: number[] = [];
  for (let h = Math.ceil(open / 60); h * 60 <= open + span; h++) {
    hours.push(h);
  }
  const bays = dealership.serviceBays.filter((bay) => bay.active);

  return (
    <div className="flex flex-col">
      {appointments.length === 0 && (
        <div className="mb-4">
          <EmptyState title="No appointments on this day." />
        </div>
      )}
      <div aria-hidden="true" className="relative hidden h-6 md:ml-32 md:block">
        {hours.map((h) => (
          <span key={h} className="absolute -translate-x-1/2 text-xs text-slate" style={{ left: `${((h * 60 - open) / span) * 100}%` }}>
            {String(h).padStart(2, '0')}:00
          </span>
        ))}
      </div>
      {bays.map((bay) => (
        <section key={bay.id} aria-label={bay.name} className="grid gap-2 border-t border-line py-3 md:grid-cols-[8rem_1fr]">
          <h2 className="font-semibold text-navy">{bay.name}</h2>
          <ol className="relative flex flex-col gap-2 md:block md:h-20">
            {appointments
              .filter((a) => a.serviceBay.id === bay.id)
              .map((a) => {
                const left = (minutesSince(a.startTime, dealership.opens, a.timeZone) / span) * 100;
                const width = ((Date.parse(a.endTime) - Date.parse(a.startTime)) / 60_000 / span) * 100;
                return (
                  <li
                    key={a.id}
                    style={{ '--left': `${left}%`, '--width': `${width}%` } as CSSProperties}
                    className="overflow-hidden rounded-md border-l-4 border-ocean bg-mist px-3 py-2 text-sm md:absolute md:inset-y-1 md:left-[var(--left)] md:w-[var(--width)]"
                  >
                    <p className="font-semibold text-ink">{formatTimeRange(a.startTime, a.endTime, a.timeZone)}</p>
                    <p className="truncate text-ink">{serviceName(a.serviceTypeId)}</p>
                    <p className="truncate text-slate">{a.technician.name}</p>
                  </li>
                );
              })}
          </ol>
        </section>
      ))}
    </div>
  );
}
