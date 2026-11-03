package com.keyloop.scheduler.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

    @Test
    void picksSpecificCodesForRoutingErrors() {
        assertThat(ErrorCode.forStatus(HttpStatus.NOT_FOUND)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.forStatus(HttpStatus.METHOD_NOT_ALLOWED)).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void fallsBackToRequestRejectedForOtherClientErrors() {
        assertThat(ErrorCode.forStatus(HttpStatus.BAD_REQUEST)).isEqualTo(ErrorCode.REQUEST_REJECTED);
        assertThat(ErrorCode.forStatus(HttpStatus.GONE)).isEqualTo(ErrorCode.REQUEST_REJECTED);
        assertThat(ErrorCode.forStatus(HttpStatus.CONTENT_TOO_LARGE)).isEqualTo(ErrorCode.REQUEST_REJECTED);
    }

    @Test
    void fallsBackToInternalErrorForServerErrors() {
        assertThat(ErrorCode.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.forStatus(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void fillsTheDetailTemplate() {
        assertThat(ErrorCode.DEALERSHIP_NOT_FOUND.detail(999L)).isEqualTo("Dealership 999 does not exist.");
        assertThat(ErrorCode.VALIDATION_FAILED.detail()).isEqualTo("Request validation failed.");
        assertThat(ErrorCode.APPOINTMENT_NOT_FOUND.detail("6f1c0a52-0000-4000-8000-000000000000"))
                .isEqualTo("Appointment 6f1c0a52-0000-4000-8000-000000000000 does not exist.");
        assertThat(ErrorCode.VEHICLE_NOT_OWNED_BY_CUSTOMER.detail(7L, 3L))
                .isEqualTo("Vehicle 7 does not belong to customer 3.");
    }

    @Test
    void bookingCodesUseConflictForResourceStateAndUnprocessableForBrokenRules() {
        assertThat(ErrorCode.NO_AVAILABILITY.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.APPOINTMENT_ALREADY_STARTED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.START_TIME_NOT_ON_GRID.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void everyCodeIsUpperSnakeCaseAndSpecified() {
        String specs = specText();
        assertThat(ErrorCode.values()).allSatisfy(code -> {
            assertThat(code.name()).matches("[A-Z]+(_[A-Z]+)*");
            assertThat(specs).as("a spec under openspec/ defining `%s`", code.name()).contains("`" + code.name() + "`");
        });
    }

    private static String specText() {
        try (Stream<Path> files = Files.walk(Path.of("openspec"))) {
            return files.filter(file -> file.getFileName().toString().equals("spec.md"))
                    .map(ErrorCodeTest::read)
                    .collect(Collectors.joining("\n"));
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void namesAreUnique() {
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name)).doesNotHaveDuplicates();
    }
}
