package com.keyloop.scheduler.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A technician's qualification for a service type. Appointments reference it, so it is retired with
 * {@code active = false} instead of being deleted; a retired qualification no longer qualifies.
 */
@Entity
@Table(name = "technician_skill")
public class TechnicianSkill {

    @EmbeddedId
    private Key id;

    @Column(nullable = false)
    private boolean active;

    protected TechnicianSkill() {
    }

    public long getTechnicianId() {
        return this.id.technicianId();
    }

    public long getServiceTypeId() {
        return this.id.serviceTypeId();
    }

    public boolean isActive() {
        return this.active;
    }

    @Embeddable
    public record Key(@Column(name = "technician_id") long technicianId,
            @Column(name = "service_type_id") long serviceTypeId) {
    }
}
