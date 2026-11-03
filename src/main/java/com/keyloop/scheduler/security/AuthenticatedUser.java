package com.keyloop.scheduler.security;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The caller of a request, read from a verified Supabase access token. Controllers receive it as
 * {@code @AuthenticationPrincipal}; {@link AccessPolicy} decides what it may do.
 *
 * @param userId the Supabase user id (the token's {@code sub}); the only caller detail that is logged
 * @param role {@code null} when the token carries no known role
 * @param customerId the customer record a {@code customer} acts for
 * @param dealershipId the dealership an {@code advisor} works at
 */
public record AuthenticatedUser(UUID userId, @Nullable Role role, @Nullable Long customerId,
        @Nullable Long dealershipId) {
}
