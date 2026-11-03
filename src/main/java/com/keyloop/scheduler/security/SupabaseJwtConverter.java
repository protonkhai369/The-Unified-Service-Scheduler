package com.keyloop.scheduler.security;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Turns a verified Supabase access token into the request's authentication, with an
 * {@link AuthenticatedUser} as principal. Reads only {@code sub} and {@code app_metadata}, which only
 * administrators can change (Supabase dashboard or Admin API). {@code user_metadata} is writable by
 * the user and never read. A missing or malformed role or id becomes {@code null}, which
 * {@link AccessPolicy} treats as no permission.
 */
public class SupabaseJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Pattern ID = Pattern.compile("[0-9]{1,18}");

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<?, ?> metadata = jwt.getClaims().get("app_metadata") instanceof Map<?, ?> map ? map : Map.of();
        Role role = role(metadata.get("scheduler_role"));
        AuthenticatedUser user = new AuthenticatedUser(userId(jwt), role, id(metadata.get("customer_id")),
                id(metadata.get("dealership_id")));
        List<GrantedAuthority> authorities = role != null ? List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
                : List.of();
        return new SupabaseAuthentication(user, jwt, authorities);
    }

    /** Supabase user ids are UUIDs; anything else is not a user token and answers 401. */
    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(String.valueOf(jwt.getSubject()));
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidBearerTokenException("The token's subject is not a user id");
        }
    }

    private static @Nullable Role role(@Nullable Object value) {
        if (value instanceof String name) {
            for (Role role : Role.values()) {
                if (role.name().equalsIgnoreCase(name)) {
                    return role;
                }
            }
        }
        return null;
    }

    /** A JSON integer, or a string of digits, as an administrator may type it in the dashboard. */
    private static @Nullable Long id(@Nullable Object value) {
        return switch (value) {
            case Long number -> number;
            case Integer number -> number.longValue();
            case String text when ID.matcher(text).matches() -> Long.valueOf(text);
            case null, default -> null;
        };
    }

    /** Authenticated by Supabase: the user as principal, the verified token as credentials. */
    static final class SupabaseAuthentication extends AbstractAuthenticationToken {

        private final AuthenticatedUser user;

        private final Jwt token;

        SupabaseAuthentication(AuthenticatedUser user, Jwt token, List<GrantedAuthority> authorities) {
            super(authorities);
            this.user = user;
            this.token = token;
            setAuthenticated(true);
        }

        @Override
        public AuthenticatedUser getPrincipal() {
            return this.user;
        }

        @Override
        public Jwt getCredentials() {
            return this.token;
        }

        @Override
        public String getName() {
            return this.user.userId().toString();
        }
    }
}
