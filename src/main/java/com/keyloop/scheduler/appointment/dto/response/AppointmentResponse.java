package com.keyloop.scheduler.appointment.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.keyloop.scheduler.appointment.entity.Appointment;
import com.keyloop.scheduler.appointment.entity.AppointmentStatus;
import com.keyloop.scheduler.appointment.repository.AppointmentView;

/**
 * An appointment as clients see it. {@code startTime} and {@code endTime} carry the dealership's
 * offset at that instant, and {@code timeZone} names the dealership's zone.
 */
public record AppointmentResponse(UUID id, AppointmentStatus status, long dealershipId, long customerId,
        long vehicleId, long serviceTypeId, ResourceRef serviceBay, ResourceRef technician, OffsetDateTime startTime,
        OffsetDateTime endTime, String timeZone) {

    public static AppointmentResponse of(Appointment appointment, ZoneId zone, String serviceBayName,
            String technicianName) {
        return new AppointmentResponse(appointment.getId(), appointment.getStatus(), appointment.getDealershipId(),
                appointment.getCustomerId(), appointment.getVehicleId(), appointment.getServiceTypeId(),
                new ResourceRef(appointment.getServiceBayId(), serviceBayName),
                new ResourceRef(appointment.getTechnicianId(), technicianName),
                appointment.getStartAt().atZone(zone).toOffsetDateTime(),
                appointment.getEndAt().atZone(zone).toOffsetDateTime(), zone.getId());
    }

    public static AppointmentResponse of(AppointmentView view) {
        return of(view.appointment(), ZoneId.of(view.timeZone()), view.serviceBayName(), view.technicianName());
    }
}
