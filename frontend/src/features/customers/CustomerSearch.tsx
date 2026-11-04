import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { useApi } from '../../lib/ApiProvider';
import { ErrorAlert } from '../../lib/ErrorAlert';
import type { Customer, Items } from '../../lib/types';
import { EmptyState, OptionCard, Spinner, TextField } from '../../ui';
import { vehicleName } from '../booking/BookingFlow';

const PAUSE_MS = 300;
const MIN_LENGTH = 2;

function useDebounced(value: string, ms: number): string {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return debounced;
}

type CustomerSearchProps = {
  query: string;
  onQuery: (query: string) => void;
  onChoose: (customer: Customer, vehicleId: number) => void;
};

export function CustomerSearch({ query, onQuery, onChoose }: CustomerSearchProps) {
  const api = useApi();
  const term = useDebounced(query.trim(), PAUSE_MS);
  const searchable = term.length >= MIN_LENGTH;
  const results = useQuery({
    queryKey: ['customers', term],
    queryFn: async () => (await api.get<Items<Customer>>(`/api/v1/customers?q=${encodeURIComponent(term)}`)).items,
    enabled: searchable,
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="max-w-md">
        <TextField label="Find a customer" type="search" autoComplete="off" value={query} onChange={(e) => onQuery(e.target.value)} hint="Name or e-mail, at least two characters. Accents and capitals do not matter." />
      </div>
      {searchable && results.isPending && <Spinner label="Searching" />}
      {searchable && results.isError && <ErrorAlert error={results.error} />}
      {searchable && results.data?.length === 0 && <EmptyState title={`No customer matches “${term}”.`}>Check the spelling, or try part of the e-mail.</EmptyState>}
      {searchable && results.data && results.data.length > 0 && (
        <ul className="flex flex-col gap-3">
          {results.data.map((customer) => (
            <li key={customer.id} role="group" aria-label={customer.name} className="flex flex-col gap-3 rounded-lg border border-line p-4">
              <div>
                <p className="font-semibold text-navy">{customer.name}</p>
                <p className="text-slate">{[customer.email, customer.phone].filter(Boolean).join(' · ')}</p>
              </div>
              {customer.vehicles.length === 0 ? (
                <p className="text-slate">No vehicles on this account.</p>
              ) : (
                <div className="grid gap-2 sm:grid-cols-2">
                  {customer.vehicles.map((v) => (
                    <OptionCard key={v.id} title={vehicleName(v)} description={[v.modelYear, v.vin].filter(Boolean).join(' · ')} selected={false} onSelect={() => onChoose(customer, v.id)} />
                  ))}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
