package com.keyloop.scheduler.security;

import java.util.Objects;

/**
 * Who may do what with appointments (access-control spec). Pure: no Spring, no database, so every
 * rule is unit-tested. A customer acts only for their own customer record, at any dealership; an
 * advisor acts for any customer at their own dealership; an admin acts everywhere. A role without
 * the id it needs allows nothing.
 */
public final class AccessPolicy {

    private AccessPolicy() {
    }

    /** Whether the caller has a usable role at all; otherwise every appointment endpoint answers 403. */
    public static boolean mayUseAppointments(AuthenticatedUser user) {
        return switch (user.role()) {
            case ADMIN -> true;
            case CUSTOMER -> user.customerId() != null;
            case ADVISOR -> user.dealershipId() != null;
            case null -> false;
        };
    }

    /** Whether the caller may book for this customer at this dealership. */
    public static boolean mayBook(AuthenticatedUser user, long dealershipId, long customerId) {
        return actsFor(user, dealershipId, customerId);
    }

    /** Whether the caller may read or cancel an appointment of this customer at this dealership. */
    public static boolean maySee(AuthenticatedUser user, long appointmentDealershipId, long appointmentCustomerId) {
        return actsFor(user, appointmentDealershipId, appointmentCustomerId);
    }

    public static boolean isLinkedCustomer(AuthenticatedUser user) {
        return user.role() == Role.CUSTOMER && user.customerId() != null;
    }

    public static boolean mayViewSchedule(AuthenticatedUser user, long dealershipId) {
        return switch (user.role()) {
            case ADMIN -> true;
            case ADVISOR -> Objects.equals(user.dealershipId(), dealershipId);
            case CUSTOMER -> false;
            case null -> false;
        };
    }

    private static boolean actsFor(AuthenticatedUser user, long dealershipId, long customerId) {
        return switch (user.role()) {
            case ADMIN -> true;
            case CUSTOMER -> Objects.equals(user.customerId(), customerId);
            case ADVISOR -> Objects.equals(user.dealershipId(), dealershipId);
            case null -> false;
        };
    }
}
