package com.keyloop.scheduler.appointment;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient.BodyContentSpec;
import org.springframework.test.web.servlet.client.RestTestClient.RequestBodySpec;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;
import com.keyloop.scheduler.DatabaseIntegrationTest;

public abstract class AppointmentApiTestSupport extends DatabaseIntegrationTest {

    protected static final String APPOINTMENTS = "/api/v1/appointments";

    protected static final String AVAILABILITY = "/api/v1/dealerships/{dealershipId}/availability";

    protected static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    protected Workshop london(int durationMinutes, int bays, int technicians) {
        return fixtures.workshop("London", "Europe/London", LocalTime.of(8, 0), LocalTime.of(18, 0), durationMinutes,
                bays, technicians);
    }

    protected Workshop saigon(int durationMinutes, int bays, int technicians) {
        return fixtures.workshop("Saigon", "Asia/Ho_Chi_Minh", LocalTime.of(7, 30), LocalTime.of(17, 30),
                durationMinutes, bays, technicians);
    }

    protected ResponseSpec book(Map<String, Object> body) {
        return book(body, null);
    }

    protected ResponseSpec book(Map<String, Object> body, @Nullable String idempotencyKey) {
        return bookAs(tokens.admin(), body, idempotencyKey);
    }

    protected ResponseSpec bookAs(String token, Map<String, Object> body) {
        return bookAs(token, body, null);
    }

    protected ResponseSpec bookAs(String token, Map<String, Object> body, @Nullable String idempotencyKey) {
        RequestBodySpec request = http.post().uri(APPOINTMENTS).contentType(APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(token));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return request.body(body).exchange();
    }

    protected Map<String, Object> booked(Map<String, Object> body) {
        return booked(body, null);
    }

    protected Map<String, Object> booked(Map<String, Object> body, @Nullable String idempotencyKey) {
        return book(body, idempotencyKey).expectStatus().isCreated()
                .expectBody(JSON_OBJECT).returnResult().getResponseBody();
    }

    protected ResponseSpec get(Object id) {
        return getAs(tokens.admin(), id);
    }

    protected ResponseSpec getAs(String token, Object id) {
        return http.get().uri(APPOINTMENTS + "/{id}", id).headers(headers -> headers.setBearerAuth(token)).exchange();
    }

    protected ResponseSpec cancel(Object id) {
        return cancelAs(tokens.admin(), id);
    }

    protected ResponseSpec cancelAs(String token, Object id) {
        return http.post().uri(APPOINTMENTS + "/{id}/cancel", id).headers(headers -> headers.setBearerAuth(token))
                .exchange();
    }

    protected ResponseSpec availability(long dealershipId, long serviceTypeId, String date) {
        return http.get().uri(AVAILABILITY + "?serviceTypeId={serviceTypeId}&date={date}", dealershipId, serviceTypeId,
                date).headers(headers -> headers.setBearerAuth(tokens.admin())).exchange();
    }

    protected List<Map<String, Object>> slots(Workshop workshop, String date) {
        return slots(workshop.dealershipId(), workshop.serviceTypeId(), date);
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> slots(long dealershipId, long serviceTypeId, String date) {
        Map<String, Object> body = availability(dealershipId, serviceTypeId, date).expectStatus().isOk()
                .expectBody(JSON_OBJECT).returnResult().getResponseBody();
        return (List<Map<String, Object>>) body.get("slots");
    }

    protected static List<String> startTimes(List<Map<String, Object>> slots) {
        return slots.stream().map(slot -> (String) slot.get("startTime")).toList();
    }

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
