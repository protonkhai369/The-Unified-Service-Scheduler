package com.keyloop.scheduler.customer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.keyloop.scheduler.customer.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
