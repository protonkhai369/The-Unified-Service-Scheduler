package com.keyloop.scheduler.availability.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.keyloop.scheduler.scheduling.Slot;

public record SlotResponse(OffsetDateTime startTime, OffsetDateTime endTime, int availableBays,
        int availableTechnicians) {

    public static SlotResponse of(Slot slot, ZoneId zone) {
        return new SlotResponse(slot.range().start().atZone(zone).toOffsetDateTime(),
                slot.range().end().atZone(zone).toOffsetDateTime(), slot.availableBays(), slot.availableTechnicians());
    }
}
