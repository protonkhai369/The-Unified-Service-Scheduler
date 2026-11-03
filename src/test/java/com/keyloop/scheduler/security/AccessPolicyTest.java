package com.keyloop.scheduler.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * access-control spec, "Roles from the token", "Booking permissions" and "Appointment visibility".
 * Dealership 2 with customer 2 is "own" for the callers below; dealership 1 and customer 1 are not.
 */
class AccessPolicyTest {

    private static final AuthenticatedUser ADMIN = user(Role.ADMIN, null, null);

    private static final AuthenticatedUser CUSTOMER_2 = user(Role.CUSTOMER, 2L, null);

    private static final AuthenticatedUser ADVISOR_AT_2 = user(Role.ADVISOR, null, 2L);

    @Test
    void adminMayDoEverything() {
        assertThat(AccessPolicy.mayUseAppointments(ADMIN)).isTrue();
        assertThat(AccessPolicy.mayBook(ADMIN, 1, 1)).isTrue();
        assertThat(AccessPolicy.maySee(ADMIN, 1, 1)).isTrue();
    }

    @Test
    void customerActsOnlyForTheirOwnCustomerRecord() {
        assertThat(AccessPolicy.mayUseAppointments(CUSTOMER_2)).isTrue();
        assertThat(AccessPolicy.mayBook(CUSTOMER_2, 1, 2)).as("any dealership").isTrue();
        assertThat(AccessPolicy.mayBook(CUSTOMER_2, 2, 1)).isFalse();
        assertThat(AccessPolicy.maySee(CUSTOMER_2, 1, 2)).isTrue();
        assertThat(AccessPolicy.maySee(CUSTOMER_2, 2, 1)).as("even at the same dealership").isFalse();
    }

    @Test
    void advisorActsOnlyAtTheirOwnDealership() {
        assertThat(AccessPolicy.mayUseAppointments(ADVISOR_AT_2)).isTrue();
        assertThat(AccessPolicy.mayBook(ADVISOR_AT_2, 2, 1)).as("any customer").isTrue();
        assertThat(AccessPolicy.mayBook(ADVISOR_AT_2, 1, 2)).isFalse();
        assertThat(AccessPolicy.maySee(ADVISOR_AT_2, 2, 1)).isTrue();
        assertThat(AccessPolicy.maySee(ADVISOR_AT_2, 1, 2)).isFalse();
    }

    @Test
    void customerWithoutCustomerIdMayDoNothing() {
        assertNothingAllowed(user(Role.CUSTOMER, null, 2L));
    }

    @Test
    void advisorWithoutDealershipIdMayDoNothing() {
        assertNothingAllowed(user(Role.ADVISOR, 2L, null));
    }

    @Test
    void callerWithoutRoleMayDoNothing() {
        assertNothingAllowed(user(null, 2L, 2L));
    }

    @Test
    void onlyACustomerWithACustomerIdIsALinkedCustomer() {
        assertThat(AccessPolicy.isLinkedCustomer(CUSTOMER_2)).isTrue();
        assertThat(AccessPolicy.isLinkedCustomer(user(Role.CUSTOMER, null, 2L))).isFalse();
        assertThat(AccessPolicy.isLinkedCustomer(ADVISOR_AT_2)).isFalse();
        assertThat(AccessPolicy.isLinkedCustomer(ADMIN)).isFalse();
        assertThat(AccessPolicy.isLinkedCustomer(user(null, 2L, 2L))).isFalse();
    }

    @Test
    void scheduleIsForAdminsAndTheDealershipsOwnAdvisors() {
        assertThat(AccessPolicy.mayViewSchedule(ADMIN, 1)).isTrue();
        assertThat(AccessPolicy.mayViewSchedule(ADVISOR_AT_2, 2)).isTrue();
        assertThat(AccessPolicy.mayViewSchedule(ADVISOR_AT_2, 1)).isFalse();
        assertThat(AccessPolicy.mayViewSchedule(user(Role.ADVISOR, 2L, null), 2)).isFalse();
        assertThat(AccessPolicy.mayViewSchedule(CUSTOMER_2, 2)).isFalse();
        assertThat(AccessPolicy.mayViewSchedule(user(null, 2L, 2L), 2)).isFalse();
    }

    private static void assertNothingAllowed(AuthenticatedUser user) {
        assertThat(AccessPolicy.mayUseAppointments(user)).isFalse();
        assertThat(AccessPolicy.mayBook(user, 2, 2)).isFalse();
        assertThat(AccessPolicy.maySee(user, 2, 2)).isFalse();
    }

    private static AuthenticatedUser user(@Nullable Role role, @Nullable Long customerId, @Nullable Long dealershipId) {
        return new AuthenticatedUser(UUID.randomUUID(), role, customerId, dealershipId);
    }
}
