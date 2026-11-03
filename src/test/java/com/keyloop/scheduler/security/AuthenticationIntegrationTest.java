package com.keyloop.scheduler.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient.BodyContentSpec;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;
import com.keyloop.scheduler.appointment.AppointmentApiTestSupport;

/**
 * access-control spec, "Bearer token authentication" and "Public endpoints"; error-responses spec,
 * "Authentication error" and "Framework error" (an unknown path with a token).
 */
@DisplayName("Authentication")
class AuthenticationIntegrationTest extends AppointmentApiTestSupport {

    private static final UUID UNKNOWN_APPOINTMENT = UUID.fromString("6f1c0a52-0000-4000-8000-000000000000");

    @Test
    @DisplayName("Valid token accepted")
    void validTokenAccepted() {
        Workshop workshop = london(60, 1, 1);
        long emma = fixtures.customer("Emma");
        Map<String, Object> appointment = booked(workshop.request(emma, fixtures.vehicle(emma), "2026-11-03T10:00:00Z"));

        getAs(tokens.admin(), appointment.get("id")).expectStatus().isOk();
    }

    @Test
    @DisplayName("No token")
    void noToken() {
        Workshop workshop = london(60, 1, 1);
        long emma = fixtures.customer("Emma");

        ResponseSpec response = http.post().uri(APPOINTMENTS).contentType(APPLICATION_JSON)
                .header("X-Request-Id", "no-token-1")
                .body(workshop.request(emma, fixtures.vehicle(emma), "2026-11-03T10:00:00Z"))
                .exchange();

        unauthenticated(response)
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.instance").isEqualTo(APPOINTMENTS)
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.requestId").isEqualTo("no-token-1");
        response.expectHeader().valueEquals("X-Request-Id", "no-token-1");
        assertThat(appointmentCount()).isZero();
    }

    @Test
    @DisplayName("Expired token")
    void expiredToken() {
        unauthenticated(getAs(tokens.expired(), UNKNOWN_APPOINTMENT));
    }

    @Test
    @DisplayName("Token signed by another key")
    void tokenSignedByAnotherKey() {
        unauthenticated(getAs(tokens.signedByAnUnpublishedKey(), UNKNOWN_APPOINTMENT));
    }

    @Test
    @DisplayName("Wrong issuer or audience")
    void wrongIssuerOrAudience() {
        unauthenticated(getAs(tokens.fromAnotherProject(), UNKNOWN_APPOINTMENT));
        unauthenticated(getAs(tokens.withWrongAudience(), UNKNOWN_APPOINTMENT));
    }

    @Test
    @DisplayName("Malformed token")
    void malformedToken() {
        unauthenticated(getAs("not-a-jwt", UNKNOWN_APPOINTMENT));
    }

    @Test
    @DisplayName("Catalog without a token")
    void catalogWithoutToken() {
        http.get().uri("/api/v1/dealerships").exchange().expectStatus().isOk();
        http.get().uri("/api/v1/service-types").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("Health without a token")
    void healthWithoutToken() {
        http.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        http.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("Unknown path without a token")
    void unknownPathWithoutToken() {
        unauthenticated(http.get().uri("/api/v1/no-such-path").exchange())
                .jsonPath("$.instance").isEqualTo("/api/v1/no-such-path");
    }

    @Test
    @DisplayName("Unknown path with a token")
    void unknownPathWithToken() {
        problem(http.get().uri("/api/v1/no-such-path").headers(headers -> headers.setBearerAuth(tokens.admin()))
                .exchange(), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("Public endpoint with an invalid token")
    void publicEndpointWithInvalidToken() {
        unauthenticated(http.get().uri("/api/v1/dealerships").headers(headers -> headers.setBearerAuth(tokens.expired()))
                .exchange());
    }

    private static BodyContentSpec unauthenticated(ResponseSpec response) {
        response.expectHeader().valueEquals("WWW-Authenticate", "Bearer");
        return problem(response, 401, "UNAUTHENTICATED")
                .jsonPath("$.requestId").isNotEmpty();
    }
}
