package com.keyloop.scheduler.appointment;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;

import java.time.LocalTime;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient.BodyContentSpec;
import org.springframework.test.web.servlet.client.RestTestClient.RequestBodySpec;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;
import com.keyloop.scheduler.DatabaseIntegrationTest;

/** HTTP helpers shared by the appointment tests; same context as every other integration test. */
public abstract class AppointmentApiTestSupport extends DatabaseIntegrationTest {

    protected static final String APPOINTMENTS = "/api/v1/appointments";

    protected static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    /** A London dealership (UTC+0 in November) open 08:00-18:00. */
    protected Workshop london(int durationMinutes, int bays, int technicians) {
        return fixtures.workshop("London", "Europe/London", LocalTime.of(8, 0), LocalTime.of(18, 0), durationMinutes,
                bays, technicians);
    }

    /** A Saigon dealership (UTC+7) open 07:30-17:30. */
    protected Workshop saigon(int durationMinutes, int bays, int technicians) {
        return fixtures.workshop("Saigon", "Asia/Ho_Chi_Minh", LocalTime.of(7, 30), LocalTime.of(17, 30),
                durationMinutes, bays, technicians);
    }

    protected ResponseSpec book(Map<String, Object> body) {
        return book(body, null);
    }

    protected ResponseSpec book(Map<String, Object> body, @Nullable String idempotencyKey) {
        RequestBodySpec request = http.post().uri(APPOINTMENTS).contentType(APPLICATION_JSON);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return request.body(body).exchange();
    }

    /** Books and returns the confirmed appointment's JSON body. */
    protected Map<String, Object> booked(Map<String, Object> body) {
        return booked(body, null);
    }

    protected Map<String, Object> booked(Map<String, Object> body, @Nullable String idempotencyKey) {
        return book(body, idempotencyKey).expectStatus().isCreated()
                .expectBody(JSON_OBJECT).returnResult().getResponseBody();
    }

    protected ResponseSpec get(Object id) {
        return http.get().uri(APPOINTMENTS + "/{id}", id).exchange();
    }

    protected ResponseSpec cancel(Object id) {
        return http.post().uri(APPOINTMENTS + "/{id}/cancel", id).exchange();
    }

    /** Asserts a problem response with the given status and code, and returns its body for more checks. */
    protected static BodyContentSpec problem(ResponseSpec response, int status, String code) {
        return response.expectStatus().isEqualTo(status)
                .expectHeader().contentType(APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(status)
                .jsonPath("$.code").isEqualTo(code);
    }

    protected long appointmentCount() {
        return jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single();
    }

    @SuppressWarnings("unchecked")
    protected static long idOf(Map<String, Object> body, String resource) {
        return ((Number) ((Map<String, Object>) body.get(resource)).get("id")).longValue();
    }
}
