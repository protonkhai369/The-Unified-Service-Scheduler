package com.keyloop.scheduler.scheduling;

public record Slot(TimeRange range, int availableBays, int availableTechnicians) {
}
