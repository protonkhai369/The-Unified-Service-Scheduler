package com.keyloop.scheduler.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** access-control spec, "Roles from the token": what the converter reads from a verified token. */
class SupabaseJwtConverterTest {

    private static final UUID USER = UUID.fromString("5a1f0c3e-7d2b-4c9a-9e1f-2b3c4d5e6f70");

    private final SupabaseJwtConverter converter = new SupabaseJwtConverter();

    @Test
    void readsEachRoleWithItsId() {
        assertThat(user(Map.of("scheduler_role", "admin"))).isEqualTo(new AuthenticatedUser(USER, Role.ADMIN, null, null));
        assertThat(user(Map.of("scheduler_role", "customer", "customer_id", 2L)))
                .isEqualTo(new AuthenticatedUser(USER, Role.CUSTOMER, 2L, null));
        assertThat(user(Map.of("scheduler_role", "advisor", "dealership_id", 2L)))
                .isEqualTo(new AuthenticatedUser(USER, Role.ADVISOR, null, 2L));
    }

    @Test
    void grantsTheRoleAsAnAuthority() {
        assertThat(authorities(Map.of("scheduler_role", "admin"))).containsExactly("ROLE_ADMIN");
        assertThat(authorities(Map.of("scheduler_role", "advisor", "dealership_id", 2L))).containsExactly("ROLE_ADVISOR");
        assertThat(authorities(Map.of())).isEmpty();
    }

    @Test
    void roleIsCaseInsensitive() {
        assertThat(user(Map.of("scheduler_role", "Admin")).role()).isEqualTo(Role.ADMIN);
        assertThat(user(Map.of("scheduler_role", "ADVISOR")).role()).isEqualTo(Role.ADVISOR);
    }

    @Test
    void unknownOrMissingRoleIsNull() {
        assertThat(user(Map.of("scheduler_role", "superuser")).role()).isNull();
        assertThat(user(Map.of("scheduler_role", 1)).role()).isNull();
        assertThat(user(Map.of()).role()).isNull();
        assertThat(convert(jwt().build()).getPrincipal()).isEqualTo(new AuthenticatedUser(USER, null, null, null));
    }

    @Test
    void idsAreJsonNumbersOrNumericStrings() {
        assertThat(user(Map.of("customer_id", 2L)).customerId()).isEqualTo(2L);
        assertThat(user(Map.of("customer_id", 2)).customerId()).isEqualTo(2L);
        assertThat(user(Map.of("customer_id", "2")).customerId()).isEqualTo(2L);
        assertThat(user(Map.of("dealership_id", "17")).dealershipId()).isEqualTo(17L);
    }

    @Test
    void anythingElseIsNoId() {
        assertThat(user(Map.of("customer_id", "two")).customerId()).isNull();
        assertThat(user(Map.of("customer_id", 2.5)).customerId()).isNull();
        assertThat(user(Map.of("customer_id", "-2")).customerId()).isNull();
        assertThat(user(Map.of("customer_id", "99999999999999999999")).customerId()).isNull();
        assertThat(user(Map.of("dealership_id", Map.of("id", 2))).dealershipId()).isNull();
    }

    @Test
    void userMetadataIsIgnored() {
        Jwt token = jwt()
                .claim("app_metadata", Map.of("provider", "email"))
                .claim("user_metadata", Map.of("scheduler_role", "admin", "customer_id", 1, "dealership_id", 1))
                .build();

        assertThat(convert(token).getPrincipal()).isEqualTo(new AuthenticatedUser(USER, null, null, null));
        assertThat(convert(token).getAuthorities()).isEmpty();
    }

    @Test
    void authenticationCarriesTheUserAndTheToken() {
        Jwt token = jwt().claim("app_metadata", Map.of("scheduler_role", "admin")).build();

        AbstractAuthenticationToken authentication = convert(token);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo(USER.toString());
        assertThat(authentication.getCredentials()).isSameAs(token);
    }

    @Test
    void subjectThatIsNotAUserIdIsAnInvalidToken() {
        assertThatThrownBy(() -> convert(jwt().subject("service").build())).isInstanceOf(InvalidBearerTokenException.class);
    }

    private AuthenticatedUser user(Map<String, Object> appMetadata) {
        return (AuthenticatedUser) convert(jwt().claim("app_metadata", appMetadata).build()).getPrincipal();
    }

    private Iterable<String> authorities(Map<String, Object> appMetadata) {
        return convert(jwt().claim("app_metadata", appMetadata).build()).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
    }

    private AbstractAuthenticationToken convert(Jwt token) {
        return converter.convert(token);
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "ES256").subject(USER.toString())
                .audience(List.of("authenticated"));
    }
}
