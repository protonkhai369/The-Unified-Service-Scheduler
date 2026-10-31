package com.keyloop.scheduler.catalog.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.keyloop.scheduler.catalog.dto.response.DealershipResponse;
import com.keyloop.scheduler.catalog.dto.response.DealershipSummaryResponse;
import com.keyloop.scheduler.catalog.dto.response.ServiceBayResponse;
import com.keyloop.scheduler.catalog.dto.response.TechnicianResponse;
import com.keyloop.scheduler.catalog.entity.Dealership;
import com.keyloop.scheduler.catalog.entity.Technician;
import com.keyloop.scheduler.catalog.entity.TechnicianSkill;
import com.keyloop.scheduler.catalog.repository.DealershipRepository;
import com.keyloop.scheduler.catalog.repository.ServiceBayRepository;
import com.keyloop.scheduler.catalog.repository.TechnicianRepository;
import com.keyloop.scheduler.catalog.repository.TechnicianSkillRepository;
import com.keyloop.scheduler.web.error.ApiException;
import com.keyloop.scheduler.web.error.ErrorCode;

/** Read-only dealerships with their bays and technicians. Catalog rows change only through migrations. */
@Service
@Transactional(readOnly = true)
public class DealershipService {

    private final DealershipRepository dealerships;

    private final ServiceBayRepository serviceBays;

    private final TechnicianRepository technicians;

    private final TechnicianSkillRepository skills;

    DealershipService(DealershipRepository dealerships, ServiceBayRepository serviceBays,
            TechnicianRepository technicians, TechnicianSkillRepository skills) {
        this.dealerships = dealerships;
        this.serviceBays = serviceBays;
        this.technicians = technicians;
        this.skills = skills;
    }

    public List<DealershipSummaryResponse> dealerships() {
        return this.dealerships.findAll(Sort.by("id")).stream().map(DealershipSummaryResponse::of).toList();
    }

    /** Four queries whatever the number of technicians: dealership, bays, technicians, their skills. */
    public DealershipResponse dealership(long id) {
        Dealership dealership = this.dealerships.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.DEALERSHIP_NOT_FOUND, id));
        List<ServiceBayResponse> bays = this.serviceBays.findByDealershipIdOrderById(id).stream()
                .map(ServiceBayResponse::of)
                .toList();
        List<Technician> staff = this.technicians.findByDealershipIdOrderById(id);
        Map<Long, List<Long>> qualifications = staff.isEmpty() ? Map.of()
                : this.skills.findActiveByTechnicianIds(staff.stream().map(Technician::getId).toList()).stream()
                        .collect(Collectors.groupingBy(TechnicianSkill::getTechnicianId,
                                Collectors.mapping(TechnicianSkill::getServiceTypeId, Collectors.toList())));
        List<TechnicianResponse> technicianResponses = staff.stream()
                .map(technician -> TechnicianResponse.of(technician,
                        qualifications.getOrDefault(technician.getId(), List.of())))
                .toList();
        return DealershipResponse.of(dealership, bays, technicianResponses);
    }
}
