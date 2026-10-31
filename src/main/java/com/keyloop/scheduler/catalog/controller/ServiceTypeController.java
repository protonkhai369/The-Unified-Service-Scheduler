package com.keyloop.scheduler.catalog.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.keyloop.scheduler.catalog.dto.response.ServiceTypeResponse;
import com.keyloop.scheduler.catalog.service.ServiceTypeService;
import com.keyloop.scheduler.web.dto.ItemsResponse;

/** Read-only service types, so clients can discover the ids and durations a booking needs. */
@RestController
@RequestMapping("/api/v1/service-types")
class ServiceTypeController {

    private final ServiceTypeService serviceTypes;

    ServiceTypeController(ServiceTypeService serviceTypes) {
        this.serviceTypes = serviceTypes;
    }

    @GetMapping
    ItemsResponse<ServiceTypeResponse> serviceTypes() {
        return new ItemsResponse<>(this.serviceTypes.serviceTypes());
    }
}
