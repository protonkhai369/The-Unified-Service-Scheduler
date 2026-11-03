package com.keyloop.scheduler.availability.dto.response;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.keyloop.scheduler.catalog.entity.Dealership;
import com.keyloop.scheduler.catalog.entity.ServiceType;
import com.keyloop.scheduler.scheduling.Slot;

public record AvailabilityResponse(long dealershipId, long serviceTypeId, LocalDate date, String timeZone,
        int durationMinutes, List<SlotResponse> slots) {

    public static AvailabilityResponse of(Dealership dealership, ServiceType serviceType, LocalDate date,
            List<Slot> slots) {
        ZoneId zone = ZoneId.of(dealership.getTimeZone());
        return new AvailabilityResponse(dealership.getId(), serviceType.getId(), date, zone.getId(),
                serviceType.getDurationMinutes(), slots.stream().map(slot -> SlotResponse.of(slot, zone)).toList());
    }
}
