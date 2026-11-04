import { useQueryClient } from '@tanstack/react-query';
import { Check } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';

import { ApiError } from '../../lib/api';
import { ErrorAlert } from '../../lib/ErrorAlert';
import { matchesQuery } from '../../lib/normalize';
import { addDays, formatDate, formatTime, formatTimeRange, localDateOf, todayIn } from '../../lib/time';
import type { Appointment, Dealership, ServiceType, Vehicle } from '../../lib/types';
import { Alert, Button, EmptyState, OptionCard, SelectField, Spinner, TextField } from '../../ui';
import { useAvailability, useBook, useDealerships, useServiceTypes } from './queries';

type Step = 'vehicle' | 'service' | 'when' | 'time' | 'confirm' | 'done';

const STEPS: { step: Step; label: string }[] = [
  { step: 'vehicle', label: 'Vehicle' },
  { step: 'service', label: 'Service' },
  { step: 'when', label: 'Where & when' },
  { step: 'time', label: 'Time' },
  { step: 'confirm', label: 'Confirm' },
];

const RETRYABLE = new Set(['NETWORK_ERROR', 'SLOT_CONTENTION', 'INTERNAL_ERROR']);

type BookingFlowProps = {
  customerId: number;
  vehicles: Vehicle[];
  initialVehicleId?: number;
  doneActions: (restart: () => void) => ReactNode;
  onRestart: () => void;
};

export function vehicleName(vehicle: Vehicle): string {
  return `${vehicle.make} ${vehicle.model}`;
}

export function BookingFlow({ customerId, vehicles, initialVehicleId, doneActions, onRestart }: BookingFlowProps) {
  const queryClient = useQueryClient();
  const [step, setStep] = useState<Step>(initialVehicleId ? 'service' : 'vehicle');
  const [vehicleId, setVehicleId] = useState(initialVehicleId);
  const [serviceTypeId, setServiceTypeId] = useState<number>();
  const [dealershipId, setDealershipId] = useState<number>();
  const [date, setDate] = useState('');
  const [startTime, setStartTime] = useState<string>();
  const [idempotencyKey, setIdempotencyKey] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const [booked, setBooked] = useState<Appointment>();
  const submitting = useRef(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const firstRender = useRef(true);

  const services = useServiceTypes();
  const dealerships = useDealerships();
  const availability = useAvailability(dealershipId, serviceTypeId, date, step === 'time');
  const book = useBook();

  const vehicle = vehicles.find((v) => v.id === vehicleId);
  const service = services.data?.find((s) => s.id === serviceTypeId);
  const dealership = dealerships.data?.find((d) => d.id === dealershipId);
  const slot = availability.data?.slots.find((s) => s.startTime === startTime);

  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    heading.current?.focus();
  }, [step]);

  function goTo(next: Step) {
    if (next !== 'time') {
      setNotice(undefined);
    }
    if (next === 'confirm') {
      book.reset();
      setIdempotencyKey(crypto.randomUUID());
    }
    setStep(next);
  }

  function chooseDealership(id: number) {
    setDealershipId(id);
    const zone = dealerships.data?.find((d) => d.id === id)?.timeZone;
    if (zone && (!date || date < todayIn(zone))) {
      setDate(todayIn(zone));
    }
    setStartTime(undefined);
  }

  function confirm() {
    if (submitting.current || !idempotencyKey || !vehicleId || !serviceTypeId || !dealershipId || !startTime) {
      return;
    }
    submitting.current = true;
    book.mutate(
      { request: { dealershipId, customerId, vehicleId, serviceTypeId, startTime }, idempotencyKey },
      {
        onSuccess: (appointment) => {
          setBooked(appointment);
          setStep('done');
        },
        onError: (error) => {
          if (error instanceof ApiError && error.code === 'NO_AVAILABILITY') {
            setStartTime(undefined);
            setIdempotencyKey(undefined);
            setStep('time');
            setNotice('That time was just taken. Please choose another.');
            queryClient.invalidateQueries({ queryKey: ['availability'] });
          }
        },
        onSettled: () => {
          submitting.current = false;
        },
      },
    );
  }

  const title = {
    vehicle: 'Choose your vehicle',
    service: 'Choose a service',
    when: 'Choose a dealership and date',
    time: 'Choose a time',
    confirm: 'Check and confirm',
    done: 'Your service is booked',
  }[step];

  return (
    <div className="flex flex-col gap-6">
      {step !== 'done' && <Progress current={step} />}
      <h2 ref={heading} tabIndex={-1} className="text-xl font-semibold text-navy focus:outline-none">
        {title}
      </h2>

      {step === 'vehicle' && (
        <>
          {vehicles.length === 0 ? (
            <EmptyState title="No vehicles on this account.">Ask the dealership to add your vehicle before booking.</EmptyState>
          ) : (
            <div role="group" aria-label="Vehicles" className="grid gap-3 sm:grid-cols-2">
              {vehicles.map((v) => (
                <OptionCard
                  key={v.id}
                  title={vehicleName(v)}
                  description={[v.modelYear, v.vin].filter(Boolean).join(' · ')}
                  selected={vehicleId === v.id}
                  onSelect={() => setVehicleId(v.id)}
                />
              ))}
            </div>
          )}
          <Actions>
            <Button variant="primary" disabled={!vehicleId} onClick={() => goTo('service')}>
              Continue
            </Button>
          </Actions>
        </>
      )}

      {step === 'service' && (
        <ServiceStep
          services={services.data}
          loading={services.isPending}
          error={services.error}
          selected={serviceTypeId}
          onSelect={setServiceTypeId}
          onBack={() => goTo('vehicle')}
          onContinue={() => goTo('when')}
        />
      )}

      {step === 'when' && (
        <WhenStep
          dealerships={dealerships.data}
          loading={dealerships.isPending}
          error={dealerships.error}
          dealership={dealership}
          date={date}
          onDealership={chooseDealership}
          onDate={(value) => {
            setDate(value);
            setStartTime(undefined);
          }}
          onBack={() => goTo('service')}
          onContinue={() => goTo('time')}
        />
      )}

      {step === 'time' && dealership && (
        <>
          <p className="text-slate">
            {dealership.name} · {formatDate(date)} · times in {dealership.timeZone}
          </p>
          {notice && <Alert tone="error">{notice}</Alert>}
          {availability.isPending && <Spinner label="Finding free times" />}
          {availability.isError && <ErrorAlert error={availability.error} />}
          {availability.data && availability.data.slots.length === 0 && (
            <EmptyState
              title="No time is free on this day."
              action={
                <Button variant="secondary" onClick={() => goTo('when')}>
                  Choose another date
                </Button>
              }
            >
              The next day may have room: {formatDate(addDays(date, 1))}.
            </EmptyState>
          )}
          {availability.data && availability.data.slots.length > 0 && (
            <div role="group" aria-label="Free start times" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              {availability.data.slots.map((s) => {
                const places = Math.min(s.availableBays, s.availableTechnicians);
                return (
                  <OptionCard
                    key={s.startTime}
                    title={formatTime(s.startTime, dealership.timeZone)}
                    description={`${places} ${places === 1 ? 'place' : 'places'} free`}
                    selected={startTime === s.startTime}
                    onSelect={() => setStartTime(s.startTime)}
                  />
                );
              })}
            </div>
          )}
          <Actions>
            <Button variant="secondary" onClick={() => goTo('when')}>
              Back
            </Button>
            <Button variant="primary" disabled={!slot} onClick={() => goTo('confirm')}>
              Continue
            </Button>
          </Actions>
        </>
      )}

      {step === 'confirm' && vehicle && service && dealership && slot && (
        <>
          <Summary
            label="Your choices"
            rows={[
              ['Vehicle', `${vehicleName(vehicle)} · ${vehicle.vin}`],
              ['Service', `${service.name} · ${service.durationMinutes} min`],
              ['Dealership', dealership.name],
              ['Date', formatDate(date)],
              ['Time', `${formatTimeRange(slot.startTime, slot.endTime, dealership.timeZone)} (${dealership.timeZone})`],
            ]}
          />
          {book.isError && (
            <ErrorAlert
              error={book.error}
              action={
                book.error instanceof ApiError && RETRYABLE.has(book.error.code) ? (
                  <Button variant="secondary" className="self-start" onClick={confirm}>
                    Try again
                  </Button>
                ) : undefined
              }
            />
          )}
          <Actions>
            <Button variant="secondary" disabled={book.isPending} onClick={() => goTo('time')}>
              Back
            </Button>
            <Button variant="primary" busy={book.isPending} onClick={confirm}>
              Confirm booking
            </Button>
          </Actions>
        </>
      )}

      {step === 'done' && booked && (
        <>
          <Alert tone="success">
            <span className="flex items-center gap-2">
              <Check aria-hidden="true" className="size-4" />
              Booked. A bay and a technician are reserved for the whole service.
            </span>
          </Alert>
          <Summary
            label="Booking summary"
            rows={[
              ['Vehicle', vehicle ? vehicleName(vehicle) : `Vehicle ${booked.vehicleId}`],
              ['Service', service?.name ?? `Service ${booked.serviceTypeId}`],
              ['Dealership', dealership?.name ?? `Dealership ${booked.dealershipId}`],
              ['Date', formatDate(localDateOf(booked.startTime, booked.timeZone))],
              ['Time', `${formatTimeRange(booked.startTime, booked.endTime, booked.timeZone)} (${booked.timeZone})`],
              ['Bay', booked.serviceBay.name],
              ['Technician', booked.technician.name],
            ]}
          />
          <Actions>{doneActions(onRestart)}</Actions>
        </>
      )}
    </div>
  );
}

function ServiceStep(props: {
  services: ServiceType[] | undefined;
  loading: boolean;
  error: unknown;
  selected: number | undefined;
  onSelect: (id: number) => void;
  onBack: () => void;
  onContinue: () => void;
}) {
  const [filter, setFilter] = useState('');
  if (props.loading) {
    return <Spinner label="Loading services" />;
  }
  if (props.error || !props.services) {
    return <ErrorAlert error={props.error} />;
  }
  const visible = props.services.filter((s) => matchesQuery(filter, s.name, s.code));
  return (
    <>
      {props.services.length > 6 && (
        <TextField label="Filter services" type="search" value={filter} onChange={(e) => setFilter(e.target.value)} hint="Accents and capitals do not matter." />
      )}
      <div role="group" aria-label="Services" className="grid gap-3 sm:grid-cols-2">
        {visible.map((s) => (
          <OptionCard key={s.id} title={s.name} description={`${s.durationMinutes} min`} selected={props.selected === s.id} onSelect={() => props.onSelect(s.id)} />
        ))}
      </div>
      {visible.length === 0 && <EmptyState title="No service matches this filter." />}
      <Actions>
        <Button variant="secondary" onClick={props.onBack}>
          Back
        </Button>
        <Button variant="primary" disabled={!props.selected} onClick={props.onContinue}>
          Continue
        </Button>
      </Actions>
    </>
  );
}

function WhenStep(props: {
  dealerships: Dealership[] | undefined;
  loading: boolean;
  error: unknown;
  dealership: Dealership | undefined;
  date: string;
  onDealership: (id: number) => void;
  onDate: (date: string) => void;
  onBack: () => void;
  onContinue: () => void;
}) {
  if (props.loading) {
    return <Spinner label="Loading dealerships" />;
  }
  if (props.error || !props.dealerships) {
    return <ErrorAlert error={props.error} />;
  }
  const today = props.dealership ? todayIn(props.dealership.timeZone) : undefined;
  const dateError = props.date && today && props.date < today ? 'Choose today or a later date.' : undefined;
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField label="Dealership" value={props.dealership?.id ?? ''} onChange={(e) => props.onDealership(Number(e.target.value))}>
          <option value="" disabled>
            Select a dealership
          </option>
          {props.dealerships.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
        </SelectField>
        <TextField
          label="Date"
          type="date"
          value={props.date}
          min={today}
          disabled={!props.dealership}
          onChange={(e) => props.onDate(e.target.value)}
          hint={props.dealership ? `Opening hours ${props.dealership.opens}–${props.dealership.closes}, local time.` : 'Choose a dealership first.'}
          error={dateError}
        />
      </div>
      <Actions>
        <Button variant="secondary" onClick={props.onBack}>
          Back
        </Button>
        <Button variant="primary" disabled={!props.dealership || !props.date || Boolean(dateError)} onClick={props.onContinue}>
          Continue
        </Button>
      </Actions>
    </>
  );
}

function Progress({ current }: { current: Step }) {
  const index = STEPS.findIndex((s) => s.step === current);
  return (
    <ol aria-label="Booking steps" className="flex flex-wrap gap-x-4 gap-y-2 text-sm">
      {STEPS.map((s, i) => (
        <li key={s.step} aria-current={i === index ? 'step' : undefined} className={`flex items-center gap-2 ${i === index ? 'font-semibold text-navy' : 'text-slate'}`}>
          <span
            aria-hidden="true"
            className={`grid size-6 place-items-center rounded-full text-xs ${i < index ? 'bg-navy text-white' : i === index ? 'bg-yellow text-ink' : 'border border-field'}`}
          >
            {i < index ? <Check className="size-3.5" /> : i + 1}
          </span>
          {s.label}
        </li>
      ))}
    </ol>
  );
}

function Summary({ label, rows }: { label: string; rows: [string, string][] }) {
  return (
    <section aria-label={label} className="rounded-lg border border-line">
      <dl className="divide-y divide-line">
        {rows.map(([term, value]) => (
          <div key={term} className="grid gap-1 px-4 py-3 sm:grid-cols-3">
            <dt className="text-sm text-slate">{term}</dt>
            <dd className="font-medium text-ink sm:col-span-2">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function Actions({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap justify-end gap-3">{children}</div>;
}
