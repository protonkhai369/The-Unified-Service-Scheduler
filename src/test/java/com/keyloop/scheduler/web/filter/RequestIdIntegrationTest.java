package com.keyloop.scheduler.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.keyloop.scheduler.DatabaseIntegrationTest;
import com.keyloop.scheduler.web.ProbeConfiguration;

/** Observability spec: "Request correlation id" and "Access log". */
@ExtendWith(OutputCaptureExtension.class)
class RequestIdIntegrationTest extends DatabaseIntegrationTest {

    private static final String UUID_FORMAT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final String OK_PATH = ProbeConfiguration.PROBE_PATH + "/ok";

    @Test
    void echoesCallerRequestId() {
        http.get().uri(OK_PATH).header("X-Request-Id", "abc-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "abc-123");
    }

    @Test
    void generatesRequestIdWhenNoneIsSent() {
        http.get().uri(OK_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("X-Request-Id", id -> assertThat(id).matches(UUID_FORMAT));
    }

    @Test
    void replacesUnsafeRequestId() {
        http.get().uri(OK_PATH).header("X-Request-Id", "has spaces in it")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("X-Request-Id", id -> assertThat(id).matches(UUID_FORMAT));
    }

    @Test
    void addsRequestIdToErrorResponses() {
        http.get().uri("/api/v1/no-such-path").header("X-Request-Id", "err-1")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("X-Request-Id", "err-1");
    }

    @Test
    void writesOneAccessLogLineCarryingTheRequestId(CapturedOutput output) {
        http.get().uri(OK_PATH).header("X-Request-Id", "access-1").exchange().expectStatus().isOk();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut().lines()
                .filter(line -> line.contains("GET " + OK_PATH + " -> 200 ("))
                .toList())
                .singleElement()
                .satisfies(line -> assertThat(line).contains("[access-1]").containsPattern("\\(\\d+ ms\\)")));
    }

    @Test
    void doesNotLogHealthProbes(CapturedOutput output) {
        http.get().uri("/actuator/health").exchange().expectStatus().isOk();
        http.get().uri(OK_PATH).header("X-Request-Id", "after-health").exchange().expectStatus().isOk();

        await().atMost(Duration.ofSeconds(5)).until(() -> output.getOut().contains("[after-health]"));
        assertThat(output.getOut()).doesNotContain("/actuator/health ->");
    }
}
