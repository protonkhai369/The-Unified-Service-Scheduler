package com.keyloop.scheduler.catalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.keyloop.scheduler.catalog.entity.Technician;

public interface TechnicianRepository extends JpaRepository<Technician, Long> {

    List<Technician> findByDealershipIdOrderById(long dealershipId);

    /** The technicians a booking of this service type may be allocated to: active, with an active skill. */
    @Query("""
            select t from Technician t, TechnicianSkill s
            where t.dealershipId = :dealershipId and t.active = true
              and s.id.technicianId = t.id and s.id.serviceTypeId = :serviceTypeId and s.active = true
            order by t.id""")
    List<Technician> findActiveQualified(long dealershipId, long serviceTypeId);
}
