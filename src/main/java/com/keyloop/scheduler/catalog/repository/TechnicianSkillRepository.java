package com.keyloop.scheduler.catalog.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.keyloop.scheduler.catalog.entity.TechnicianSkill;

public interface TechnicianSkillRepository extends JpaRepository<TechnicianSkill, TechnicianSkill.Key> {

    @Query("""
            select s from TechnicianSkill s
            where s.id.technicianId in :technicianIds and s.active = true
            order by s.id.technicianId, s.id.serviceTypeId""")
    List<TechnicianSkill> findActiveByTechnicianIds(Collection<Long> technicianIds);
}
