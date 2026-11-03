package com.keyloop.scheduler.security;

/** The caller's role, from the access token's {@code app_metadata.scheduler_role}. */
public enum Role {

    /** Books, sees and cancels only the appointments of their own customer record. */
    CUSTOMER,

    /** Books for any customer, and sees and cancels appointments, at their own dealership. */
    ADVISOR,

    /** Everything, including the operational actuator endpoints. */
    ADMIN
}
