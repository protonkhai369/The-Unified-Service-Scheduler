import { useState } from 'react';

import { useAccount } from '../../account/MeProvider';
import { ErrorAlert } from '../../lib/ErrorAlert';
import { Button, LinkButton, Spinner } from '../../ui';
import { BookingFlow } from './BookingFlow';
import { useMyVehicles } from './queries';

export function BookServicePage() {
  const account = useAccount();
  const vehicles = useMyVehicles();
  const [attempt, setAttempt] = useState(0);

  return (
    <section className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold text-navy">Book a service</h1>
      {vehicles.isPending && <Spinner label="Loading your vehicles" />}
      {vehicles.isError && <ErrorAlert error={vehicles.error} />}
      {vehicles.data && (
        <BookingFlow
          key={attempt}
          customerId={account.customer!.id}
          vehicles={vehicles.data}
          onRestart={() => setAttempt((n) => n + 1)}
          doneActions={(restart) => (
            <>
              <Button variant="secondary" onClick={restart}>
                Book another service
              </Button>
              <LinkButton variant="primary" to="/appointments">
                See my appointments
              </LinkButton>
            </>
          )}
        />
      )}
    </section>
  );
}
