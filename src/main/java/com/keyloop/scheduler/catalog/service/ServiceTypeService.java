package com.keyloop.scheduler.catalog.service;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.keyloop.scheduler.catalog.dto.response.ServiceTypeResponse;
import com.keyloop.scheduler.catalog.repository.ServiceTypeRepository;

/** Read-only service types. Catalog rows change only through migrations and demo data. */
@Service
@Transactional(readOnly = true)
public class ServiceTypeService {

    private final ServiceTypeRepository serviceTypes;

    ServiceTypeService(ServiceTypeRepository serviceTypes) {
        this.serviceTypes = serviceTypes;
    }

    public List<ServiceTypeResponse> serviceTypes() {
        return this.serviceTypes.findAll(Sort.by("id")).stream().map(ServiceTypeResponse::of).toList();
    }
}
