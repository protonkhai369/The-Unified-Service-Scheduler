package com.keyloop.scheduler.catalog.entity;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A dealership with one daily opening window, expressed in its own time zone. */
@Entity
@Table(name = "dealership")
public class Dealership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** IANA zone id, e.g. {@code Europe/London}. */
    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(nullable = false)
    private LocalTime opens;

    @Column(nullable = false)
    private LocalTime closes;

    protected Dealership() {
    }

    public Long getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public String getTimeZone() {
        return this.timeZone;
    }

    public LocalTime getOpens() {
        return this.opens;
    }

    public LocalTime getCloses() {
        return this.closes;
    }
}
