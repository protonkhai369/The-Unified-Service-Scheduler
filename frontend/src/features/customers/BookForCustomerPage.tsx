import { useState } from 'react';

import type { Customer } from '../../lib/types';
import { Button, LinkButton } from '../../ui';
import { BookingFlow } from '../booking/BookingFlow';
import { CustomerSearch } from './CustomerSearch';

type Choice = { customer: Customer; vehicleId: number; attempt: number };

export function BookForCustomerPage() {
  const [query, setQuery] = useState('');
  const [choice, setChoice] = useState<Choice>();

  return (
    <section className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold text-navy">Book for a customer</h1>
      {!choice && <CustomerSearch query={query} onQuery={setQuery} onChoose={(customer, vehicleId) => setChoice({ customer, vehicleId, attempt: 0 })} />}
      {choice && (
        <>
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-mist px-4 py-3">
            <div>
              <p className="font-semibold text-ink">Booking for {choice.customer.name}</p>
              <p className="text-sm text-slate">{choice.customer.email}</p>
            </div>
            <Button variant="ghost" onClick={() => setChoice(undefined)}>
              Change customer
            </Button>
          </div>
          <BookingFlow
            key={`${choice.customer.id}-${choice.attempt}`}
            customerId={choice.customer.id}
            vehicles={choice.customer.vehicles}
            initialVehicleId={choice.vehicleId}
            onRestart={() => setChoice({ ...choice, attempt: choice.attempt + 1 })}
            doneActions={() => (
              <>
                <Button variant="secondary" onClick={() => setChoice(undefined)}>
                  Book for another customer
                </Button>
                <LinkButton variant="primary" to="/schedule">
                  See the day schedule
                </LinkButton>
              </>
            )}
          />
        </>
      )}
    </section>
  );
}
