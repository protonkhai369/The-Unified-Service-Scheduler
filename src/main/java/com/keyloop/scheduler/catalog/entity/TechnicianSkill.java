package com.keyloop.scheduler.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.Getter;

@Entity
@Table(name = "technician_skill")
public class TechnicianSkill {

    @EmbeddedId
    private Key id;

    @Getter
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

    @Embeddable
    public record Key(@Column(name = "technician_id") long technicianId,
            @Column(name = "service_type_id") long serviceTypeId) {
    }
}
