package com.keyloop.scheduler.catalog.dto.response;

import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.keyloop.scheduler.catalog.entity.Dealership;

/** Opening hours are local times in {@code timeZone}, pinned to {@code HH:mm}. */
public record DealershipSummaryResponse(long id, String name, String timeZone,
        @JsonFormat(pattern = "HH:mm") LocalTime opens, @JsonFormat(pattern = "HH:mm") LocalTime closes) {

    public static DealershipSummaryResponse of(Dealership dealership) {
        return new DealershipSummaryResponse(dealership.getId(), dealership.getName(), dealership.getTimeZone(),
                dealership.getOpens(), dealership.getCloses());
    }
}
