package com.keyloop.scheduler.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.keyloop.scheduler.DatabaseIntegrationTest;

/** The generated OpenAPI document (Swagger UI's source) still works next to the exception handler. */
class OpenApiIntegrationTest extends DatabaseIntegrationTest {

    @Test
    void documentsTheCatalogEndpoints() {
        http.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths").value((Map<String, Object> paths) -> assertThat(paths)
                        .containsKeys("/api/v1/service-types", "/api/v1/dealerships", "/api/v1/dealerships/{id}")
                        .doesNotContainKey("/error"))
                .jsonPath("$.paths['/api/v1/dealerships/{id}'].get.responses['200'].content['*/*'].schema['$ref']")
                .isEqualTo("#/components/schemas/DealershipResponse");
    }

    @Test
    void documentsTheAppointmentEndpointsAndTheIdempotencyKey() {
        http.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths").value((Map<String, Object> paths) -> assertThat(paths)
                        .containsKeys("/api/v1/appointments", "/api/v1/appointments/{id}",
                                "/api/v1/appointments/{id}/cancel"))
                .jsonPath("$.paths['/api/v1/appointments'].post.parameters[?(@.name == 'Idempotency-Key')].in")
                .value((List<String> in) -> assertThat(in).containsExactly("header"))
                .jsonPath("$.paths['/api/v1/appointments'].post.parameters[?(@.name == 'Idempotency-Key')].required")
                .value((List<Boolean> required) -> assertThat(required).containsExactly(false))
                .jsonPath("$.paths['/api/v1/appointments'].post.responses['201']").exists();
    }

    @Test
    void servesSwaggerUi() {
        http.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
    }
}
