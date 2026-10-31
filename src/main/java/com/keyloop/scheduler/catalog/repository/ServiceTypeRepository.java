package com.keyloop.scheduler.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.keyloop.scheduler.catalog.entity.ServiceType;

public interface ServiceTypeRepository extends JpaRepository<ServiceType, Long> {
}
