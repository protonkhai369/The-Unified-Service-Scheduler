package com.keyloop.scheduler.customer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.keyloop.scheduler.customer.entity.Vehicle;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
}
