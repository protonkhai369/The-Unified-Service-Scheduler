package com.keyloop.scheduler.appointment.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;

/**
 * A booked service appointment. Every V1 column is mapped except the generated {@code period}, which
 * only the exclusion constraints and native queries use. Other aggregates are referenced by id.
 * Status changes go through a conditional update in the repository, not through this entity.
 */
@Getter
@Entity
@Table(name = "appointment")
public class Appointment {

    /** Assigned by the application, so the id is known before the insert. */
    @Id
    private UUID id;

    @Column(name = "dealership_id", nullable = false)
    private long dealershipId;

    @Column(name = "customer_id", nullable = false)
    private long customerId;

    @Column(name = "vehicle_id", nullable = false)
    private long vehicleId;

    @Column(name = "service_type_id", nullable = false)
    private long serviceTypeId;

    @Column(name = "service_bay_id", nullable = false)
    private long serviceBayId;

    @Column(name = "technician_id", nullable = false)
    private long technicianId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppointmentStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A wrapper type: {@code null} tells Spring Data the entity is new, so it is persisted, not merged. */
    @Version
    private Long version;

    protected Appointment() {
    }

    private Appointment(UUID id, long dealershipId, long customerId, long vehicleId, long serviceTypeId,
            long serviceBayId, long technicianId, Instant startAt, Instant endAt, String idempotencyKey,
            String requestFingerprint, Instant now) {
        this.id = id;
        this.dealershipId = dealershipId;
        this.customerId = customerId;
        this.vehicleId = vehicleId;
        this.serviceTypeId = serviceTypeId;
        this.serviceBayId = serviceBayId;
        this.technicianId = technicianId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = AppointmentStatus.CONFIRMED;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A new confirmed appointment with a random id. */
    public static Appointment confirmed(long dealershipId, long customerId, long vehicleId, long serviceTypeId,
            long serviceBayId, long technicianId, Instant startAt, Instant endAt, String idempotencyKey,
            String requestFingerprint, Instant now) {
        return new Appointment(UUID.randomUUID(), dealershipId, customerId, vehicleId, serviceTypeId, serviceBayId,
                technicianId, startAt, endAt, idempotencyKey, requestFingerprint, now);
    }
}
