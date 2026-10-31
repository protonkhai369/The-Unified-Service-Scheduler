package com.keyloop.scheduler.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A service bay of one dealership; any active bay can host any service type. */
@Entity
@Table(name = "service_bay")
public class ServiceBay {

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

    protected ServiceBay() {
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
