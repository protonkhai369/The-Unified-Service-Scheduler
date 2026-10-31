package com.keyloop.scheduler.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A technician of one dealership; qualifications are {@link TechnicianSkill} rows. */
@Entity
@Table(name = "technician")
public class Technician {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Referenced by id, not as an association (aggregates reference each other by id). */
    @Column(name = "dealership_id", nullable = false)
    private long dealershipId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active;

    protected Technician() {
    }

    public Long getId() {
        return this.id;
    }

    public long getDealershipId() {
        return this.dealershipId;
    }

    public String getName() {
        return this.name;
    }

    public boolean isActive() {
        return this.active;
    }
}
