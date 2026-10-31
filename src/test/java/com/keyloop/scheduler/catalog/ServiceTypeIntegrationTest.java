package com.keyloop.scheduler.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import com.keyloop.scheduler.DatabaseIntegrationTest;

/** Dealership-catalog spec, "List service types", over HTTP on fixture data. */
class ServiceTypeIntegrationTest extends DatabaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Long>> IDS = new ParameterizedTypeReference<>() {
    };

    @Test
    void listsServiceTypesInIdOrderWithTheirDurations() {
        long oilChange = fixtures.serviceType("OIL_CHANGE", 45);
        long brakeService = fixtures.serviceType("BRAKE_SERVICE", 90);

        http.get().uri("/api/v1/service-types")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.items[*].id").value(IDS, ids -> assertThat(ids).containsExactly(oilChange, brakeService))
                .jsonPath("$.items[0].code").isEqualTo("OIL_CHANGE")
                .jsonPath("$.items[0].name").isEqualTo("Service OIL_CHANGE")
                .jsonPath("$.items[0].durationMinutes").isEqualTo(45)
                .jsonPath("$.items[1].durationMinutes").isEqualTo(90);
    }

    @Test
    void serviceTypesRejectOtherMethodsListingGet() {
        http.delete().uri("/api/v1/service-types")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().value("Allow", allow -> assertThat(allow).contains("GET"))
                .expectBody()
                .jsonPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }
}
