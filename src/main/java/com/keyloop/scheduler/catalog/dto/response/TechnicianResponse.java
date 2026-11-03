package com.keyloop.scheduler.catalog.dto.response;

import java.util.List;

import com.keyloop.scheduler.catalog.entity.Technician;

public record TechnicianResponse(long id, String name, boolean active, List<Long> qualifiedServiceTypeIds) {

    public static TechnicianResponse of(Technician technician, List<Long> qualifiedServiceTypeIds) {
        return new TechnicianResponse(technician.getId(), technician.getName(), technician.isActive(),
                qualifiedServiceTypeIds);
    }
}
