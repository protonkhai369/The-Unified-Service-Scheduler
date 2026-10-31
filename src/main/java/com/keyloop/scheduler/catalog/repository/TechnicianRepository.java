package com.keyloop.scheduler.catalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.keyloop.scheduler.catalog.entity.Technician;

public interface TechnicianRepository extends JpaRepository<Technician, Long> {

    List<Technician> findByDealershipIdOrderById(long dealershipId);
}
