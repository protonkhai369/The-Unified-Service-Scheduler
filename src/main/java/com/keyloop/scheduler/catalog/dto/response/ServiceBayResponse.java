package com.keyloop.scheduler.catalog.dto.response;

import com.keyloop.scheduler.catalog.entity.ServiceBay;

public record ServiceBayResponse(long id, String name, boolean active) {

    public static ServiceBayResponse of(ServiceBay bay) {
        return new ServiceBayResponse(bay.getId(), bay.getName(), bay.isActive());
    }
}
