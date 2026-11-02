package com.keyloop.scheduler.appointment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.keyloop.scheduler.appointment.entity.Appointment;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /**
     * The confirmed appointments of a dealership starting in {@code [from, to)}, typically one local
     * day. An appointment never crosses local midnight, so these are all of that day's appointments.
     * Served by {@code appointment_dealership_start_idx}.
     */
    @Query("""
            select new com.keyloop.scheduler.appointment.repository.BookedSlot(
                a.serviceBayId, a.technicianId, a.startAt, a.endAt)
            from Appointment a
            where a.dealershipId = :dealershipId
              and a.status = com.keyloop.scheduler.appointment.entity.AppointmentStatus.CONFIRMED
              and a.startAt >= :from and a.startAt < :to""")
    List<BookedSlot> findConfirmedStartingBetween(long dealershipId, Instant from, Instant to);

    /**
     * Whether the vehicle has a confirmed appointment overlapping {@code [start, end)} at any
     * dealership. Native, so the GiST index of {@code appointment_no_vehicle_overlap} serves it.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM appointment
                WHERE vehicle_id = :vehicleId AND status = 'CONFIRMED'
                  AND period && tstzrange(:start, :end, '[)'))""", nativeQuery = true)
    boolean vehicleBooked(long vehicleId, Instant start, Instant end);

    @Query("""
            select new com.keyloop.scheduler.appointment.repository.AppointmentView(a, d.timeZone, b.name, t.name)
            from Appointment a, Dealership d, ServiceBay b, Technician t
            where a.id = :id
              and d.id = a.dealershipId and b.id = a.serviceBayId and t.id = a.technicianId""")
    Optional<AppointmentView> findView(UUID id);

    @Query("""
            select new com.keyloop.scheduler.appointment.repository.AppointmentView(a, d.timeZone, b.name, t.name)
            from Appointment a, Dealership d, ServiceBay b, Technician t
            where a.idempotencyKey = :idempotencyKey
              and d.id = a.dealershipId and b.id = a.serviceBayId and t.id = a.technicianId""")
    Optional<AppointmentView> findViewByIdempotencyKey(String idempotencyKey);

    /**
     * Cancels the appointment if it is confirmed and has not started at {@code now}; returns the number
     * of rows changed (0 or 1). A concurrent cancellation waits for this row's lock and then re-checks
     * the condition on the committed row, so exactly one of them changes it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Appointment a
            set a.status = com.keyloop.scheduler.appointment.entity.AppointmentStatus.CANCELLED,
                a.updatedAt = :now, a.version = a.version + 1
            where a.id = :id
              and a.status = com.keyloop.scheduler.appointment.entity.AppointmentStatus.CONFIRMED
              and a.startAt > :now""")
    int cancelIfNotStarted(UUID id, Instant now);
}
