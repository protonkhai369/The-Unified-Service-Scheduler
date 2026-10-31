package com.keyloop.scheduler.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.http.MediaType.TEXT_PLAIN;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.client.RestTestClient.BodyContentSpec;

import com.keyloop.scheduler.DatabaseIntegrationTest;
import com.keyloop.scheduler.web.ProbeConfiguration;

/**
 * Error-responses spec, exercised through the test-only probe endpoints; the catalog endpoints
 * cover the scenarios that name real paths.
 */
@ExtendWith(OutputCaptureExtension.class)
class ErrorHandlingIntegrationTest extends DatabaseIntegrationTest {

    private static final String PROBE = ProbeConfiguration.PROBE_PATH;

    @Test
    void unknownPathIsProblemWithEveryMember() {
        http.get().uri("/api/v1/no-such-path").header("X-Request-Id", "abc-123")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("X-Request-Id", "abc-123")
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("No endpoint matches this path.")
                .jsonPath("$.instance").isEqualTo("/api/v1/no-such-path")
                .jsonPath("$.code").isEqualTo("NOT_FOUND")
                .jsonPath("$.requestId").isEqualTo("abc-123");
    }

    @Test
    void reportsEveryInvalidBodyField() {
        validationProblem(http.post().uri(PROBE + "/body").contentType(APPLICATION_JSON)
                .body("{\"dealershipId\": -1}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(APPLICATION_PROBLEM_JSON)
                .expectBody())
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.errors[*].field").value((List<String> fields) ->
                        assertThat(fields).containsExactlyInAnyOrder("dealershipId", "vehicleId"))
                .jsonPath("$.errors[*].message").value((List<String> messages) ->
                        assertThat(messages).allSatisfy(message -> assertThat(message).isNotBlank()));
    }

    @Test
    void namesTheFieldHoldingAValueOfTheWrongType() {
        validationProblem(http.post().uri(PROBE + "/body").contentType(APPLICATION_JSON)
                .body("{\"dealershipId\": \"abc\", \"vehicleId\": 1}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody())
                .jsonPath("$.errors[*].field").value((List<String> fields) ->
                        assertThat(fields).containsExactly("dealershipId"));
    }

    @Test
    void rejectsBodyThatIsNotJson() {
        validationProblem(http.post().uri(PROBE + "/body").contentType(APPLICATION_JSON)
                .body("{\"dealershipId\": ")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody())
                .jsonPath("$.errors").isEmpty();
    }

    @Test
    void namesMissingQueryParameter() {
        validationProblem(http.get().uri(PROBE + "/query")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody())
                .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("date"));
    }

    @Test
    void namesMissingHeader() {
        validationProblem(http.get().uri(PROBE + "/header")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody())
                .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("X-Probe"));
    }

    @Test
    void rejectsUnsupportedContentType() {
        http.post().uri(PROBE + "/body").contentType(TEXT_PLAIN).body("hello")
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectHeader().contentType(APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNSUPPORTED_MEDIA_TYPE")
                .jsonPath("$.title").isEqualTo("Unsupported Media Type");
    }

    @Test
    void rejectsUnsupportedMethodListingAllowedOnes() {
        http.delete().uri(PROBE + "/ok")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().value("Allow", allow -> assertThat(allow).contains("GET"))
                .expectBody()
                .jsonPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void keepsTheStatusOfOtherClientErrors() {
        http.get().uri(PROBE + "/gone")
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectHeader().contentType(APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(410)
                .jsonPath("$.title").isEqualTo("Gone")
                .jsonPath("$.code").isEqualTo("REQUEST_REJECTED");
    }

    @Test
    void hidesUnexpectedExceptionsAndLogsTheirStackTrace(CapturedOutput output) {
        http.get().uri(PROBE + "/failure").header("X-Request-Id", "boom-1")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().contentType(APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
                .jsonPath("$.detail").isEqualTo("An unexpected error occurred.")
                .jsonPath("$.requestId").isEqualTo("boom-1")
                .consumeWith(result -> assertThat(new String(result.getResponseBody())).doesNotContain("secret"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut())
                .containsPattern("ERROR .*\\[boom-1\\]")
                .contains("java.lang.IllegalStateException: secret internal detail"));
    }

    @Test
    void reportsFailuresOutsideSpringMvcAsProblems() {
        http.get().uri(ProbeConfiguration.FILTER_FAILURE_PATH).header("X-Request-Id", "filter-1")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().contentType(APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("X-Request-Id", "filter-1")
                .expectBody()
                .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
                .jsonPath("$.title").isEqualTo("Internal Server Error")
                .jsonPath("$.instance").isEqualTo(ProbeConfiguration.FILTER_FAILURE_PATH)
                .jsonPath("$.requestId").isEqualTo("filter-1")
                .consumeWith(result -> assertThat(new String(result.getResponseBody())).doesNotContain("secret"));
    }

    @Test
    void logsClientErrorsWithoutStackTrace(CapturedOutput output) {
        http.get().uri("/api/v1/no-such-path").header("X-Request-Id", "warn-1").exchange().expectStatus().isNotFound();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut())
                .containsPattern("WARN .*\\[warn-1\\].*404 NOT_FOUND"));
        assertThat(output.getOut()).doesNotContain("NoResourceFoundException");
    }

    private static BodyContentSpec validationProblem(BodyContentSpec body) {
        return body.jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.detail").isEqualTo("Request validation failed.")
                .jsonPath("$.requestId").isNotEmpty()
                .jsonPath("$.errors").isArray();
    }
}
