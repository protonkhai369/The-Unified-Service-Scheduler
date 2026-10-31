package com.keyloop.scheduler.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestIdFilterTest {

    private static final String UUID_FORMAT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void keepsSafeCallerId() {
        assertThat(RequestIdFilter.resolve("abc-123")).isEqualTo("abc-123");
        assertThat(RequestIdFilter.resolve("A.b_c-9")).isEqualTo("A.b_c-9");
        assertThat(RequestIdFilter.resolve("a".repeat(64))).isEqualTo("a".repeat(64));
    }

    @Test
    void generatesIdWhenCallerSendsNone() {
        assertThat(RequestIdFilter.resolve(null)).matches(UUID_FORMAT);
    }

    @Test
    void replacesUnsafeCallerIds() {
        assertThat(RequestIdFilter.resolve("abc 123")).matches(UUID_FORMAT);
        assertThat(RequestIdFilter.resolve("")).matches(UUID_FORMAT);
        assertThat(RequestIdFilter.resolve("a".repeat(65))).matches(UUID_FORMAT);
        assertThat(RequestIdFilter.resolve("abc\r\nX-Injected: 1")).matches(UUID_FORMAT);
    }

    @Test
    void generatesDifferentIds() {
        assertThat(RequestIdFilter.resolve(null)).isNotEqualTo(RequestIdFilter.resolve(null));
    }
}
