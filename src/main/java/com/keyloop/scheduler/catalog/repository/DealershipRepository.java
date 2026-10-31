package com.keyloop.scheduler.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.keyloop.scheduler.catalog.entity.Dealership;

public interface DealershipRepository extends JpaRepository<Dealership, Long> {
}
