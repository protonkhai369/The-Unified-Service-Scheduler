package com.keyloop.scheduler.scheduling;

/** Why a requested start time cannot be booked, in the order {@link StartTimeRule} checks them. */
public enum StartTimeViolation {

    /** The start is not after the current instant. */
    IN_PAST,

    /** The service would start before opening or end after closing. */
    OUTSIDE_OPENING_HOURS,

    /** The start is not a whole number of slot steps after opening time. */
    NOT_ON_GRID
}
