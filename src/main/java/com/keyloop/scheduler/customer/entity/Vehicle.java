package com.keyloop.scheduler.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;

/** A vehicle and its current owner; read-only for now (no vehicle endpoints yet). */
@Getter
@Entity
@Table(name = "vehicle")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Referenced by id, not as an association (aggregates reference each other by id). */
    @Column(name = "customer_id", nullable = false)
    private long customerId;

    @Column(nullable = false)
    private String vin;

    @Column(nullable = false)
    private String make;

    @Column(nullable = false)
    private String model;

    @Column(name = "model_year")
    private Integer modelYear;

    protected Vehicle() {
    }
}
