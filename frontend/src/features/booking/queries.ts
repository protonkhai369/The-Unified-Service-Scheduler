import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { useApi } from '../../lib/ApiProvider';
import type { Appointment, Availability, BookingRequest, Dealership, Items, ServiceType, Vehicle } from '../../lib/types';

export function useServiceTypes() {
  const api = useApi();
  return useQuery({
    queryKey: ['service-types'],
    queryFn: async () => (await api.get<Items<ServiceType>>('/api/v1/service-types')).items,
    staleTime: 5 * 60_000,
  });
}

export function useDealerships() {
  const api = useApi();
  return useQuery({
    queryKey: ['dealerships'],
    queryFn: async () => (await api.get<Items<Dealership>>('/api/v1/dealerships')).items,
    staleTime: 5 * 60_000,
  });
}

export function useMyVehicles() {
  const api = useApi();
  return useQuery({
    queryKey: ['my-vehicles'],
    queryFn: async () => (await api.get<Items<Vehicle>>('/api/v1/me/vehicles')).items,
  });
}

export function useAvailability(dealershipId: number | undefined, serviceTypeId: number | undefined, date: string, enabled: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: ['availability', dealershipId, serviceTypeId, date],
    queryFn: () =>
      api.get<Availability>(`/api/v1/dealerships/${dealershipId}/availability?serviceTypeId=${serviceTypeId}&date=${date}`),
    enabled: enabled && dealershipId !== undefined && serviceTypeId !== undefined && date !== '',
  });
}

export function useBook() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: { request: BookingRequest; idempotencyKey: string }) =>
      api.post<Appointment>('/api/v1/appointments', request, { idempotencyKey }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['my-appointments'] });
      queryClient.invalidateQueries({ queryKey: ['schedule'] });
      queryClient.invalidateQueries({ queryKey: ['availability'] });
    },
  });
}
