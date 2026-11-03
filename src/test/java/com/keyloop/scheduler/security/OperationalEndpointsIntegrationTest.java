package com.keyloop.scheduler.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.keyloop.scheduler.appointment.AppointmentApiTestSupport;

/** access-control spec, "Operational endpoints for admins". */
@DisplayName("Operational endpoints for admins")
class OperationalEndpointsIntegrationTest extends AppointmentApiTestSupport {

    @Test
    @DisplayName("Prometheus for an admin")
    void prometheusForAnAdmin() {
        String scrape = actuator("/actuator/prometheus", tokens.admin())
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(scrape).contains("scheduler_bookings_total");
        actuator("/actuator/metrics", tokens.admin()).expectStatus().isOk();
    }

    @Test
    @DisplayName("Prometheus for a customer")
    void prometheusForACustomer() {
        problem(actuator("/actuator/prometheus", tokens.customer(1)), 403, "FORBIDDEN");
        problem(actuator("/actuator/metrics/scheduler.bookings", tokens.advisor(1)), 403, "FORBIDDEN");
    }

    @Test
    void prometheusWithoutToken() {
        problem(http.get().uri("/actuator/prometheus").exchange(), 401, "UNAUTHENTICATED");
    }

    private ResponseSpec actuator(String path, String token) {
        return http.get().uri(path).headers(headers -> headers.setBearerAuth(token)).exchange();
    }
}
