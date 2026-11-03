package com.keyloop.scheduler.customer.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.keyloop.scheduler.customer.entity.Vehicle;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByCustomerIdOrderById(long customerId);

    List<Vehicle> findByCustomerIdInOrderById(Collection<Long> customerIds);
}
