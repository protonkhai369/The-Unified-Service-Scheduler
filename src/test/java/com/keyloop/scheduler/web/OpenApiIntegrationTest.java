package com.keyloop.scheduler.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.keyloop.scheduler.DatabaseIntegrationTest;

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

    /** access-control spec: Swagger UI's "Authorize" sends the token on appointment calls only. */
    @Test
    void declaresTheBearerSchemeOnTheProtectedOperations() {
        http.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components.securitySchemes.bearer.type").isEqualTo("http")
                .jsonPath("$.components.securitySchemes.bearer.scheme").isEqualTo("bearer")
                .jsonPath("$.components.securitySchemes.bearer.bearerFormat").isEqualTo("JWT")
                .jsonPath("$.paths['/api/v1/appointments'].post.security[0].bearer").isArray()
                .jsonPath("$.paths['/api/v1/appointments/{id}'].get.security[0].bearer").isArray()
                .jsonPath("$.paths['/api/v1/appointments/{id}/cancel'].post.security[0].bearer").isArray()
                .jsonPath("$.paths['/api/v1/dealerships/{dealershipId}/availability'].get.security[0].bearer").isArray()
                .jsonPath("$.paths['/api/v1/dealerships'].get.security").doesNotExist()
                .jsonPath("$.paths['/api/v1/dealerships/{id}'].get.security").doesNotExist()
                .jsonPath("$.security").doesNotExist()
                // The caller comes from the token, never from a parameter.
                .jsonPath("$.paths['/api/v1/appointments/{id}'].get.parameters[*].name")
                .value((List<String> names) -> assertThat(names).containsExactly("id"));
    }

    @Test
    void documentsTheAvailabilityEndpoint() {
        String operation = "$.paths['/api/v1/dealerships/{dealershipId}/availability'].get";
        http.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath(operation + ".parameters[?(@.name == 'serviceTypeId')].required")
                .value((List<Boolean> required) -> assertThat(required).containsExactly(true))
                .jsonPath(operation + ".parameters[?(@.name == 'date')].required")
                .value((List<Boolean> required) -> assertThat(required).containsExactly(true))
                .jsonPath(operation + ".parameters[?(@.name == 'date')].schema.format")
                .value((List<String> format) -> assertThat(format).containsExactly("date"))
                .jsonPath(operation + ".responses['200'].content['*/*'].schema['$ref']")
                .isEqualTo("#/components/schemas/AvailabilityResponse");
    }

    @Test
    void servesSwaggerUi() {
        http.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
    }
}
