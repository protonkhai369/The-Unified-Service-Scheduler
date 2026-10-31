package com.keyloop.scheduler.catalog.dto.response;

import com.keyloop.scheduler.catalog.entity.ServiceType;

public record ServiceTypeResponse(long id, String code, String name, int durationMinutes) {

    public static ServiceTypeResponse of(ServiceType serviceType) {
        return new ServiceTypeResponse(serviceType.getId(), serviceType.getCode(), serviceType.getName(),
                serviceType.getDurationMinutes());
    }
}
