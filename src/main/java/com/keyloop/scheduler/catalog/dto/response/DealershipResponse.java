package com.keyloop.scheduler.catalog.dto.response;

import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.keyloop.scheduler.catalog.entity.Dealership;

/** The fields of {@link DealershipSummaryResponse} plus the dealership's bays and technicians. */
public record DealershipResponse(long id, String name, String timeZone,
        @JsonFormat(pattern = "HH:mm") LocalTime opens, @JsonFormat(pattern = "HH:mm") LocalTime closes,
        List<ServiceBayResponse> serviceBays, List<TechnicianResponse> technicians) {

    public static DealershipResponse of(Dealership dealership, List<ServiceBayResponse> serviceBays,
            List<TechnicianResponse> technicians) {
        return new DealershipResponse(dealership.getId(), dealership.getName(), dealership.getTimeZone(),
                dealership.getOpens(), dealership.getCloses(), serviceBays, technicians);
    }
}
