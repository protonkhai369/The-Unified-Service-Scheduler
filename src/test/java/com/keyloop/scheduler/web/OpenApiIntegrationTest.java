package com.keyloop.scheduler.web;

import static org.assertj.core.api.Assertions.assertThat;

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
    void servesSwaggerUi() {
        http.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
    }
}
