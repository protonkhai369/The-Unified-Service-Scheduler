package com.keyloop.scheduler.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    private static final Instant TEN_SAIGON = OffsetDateTime.parse("2026-11-03T10:00:00+07:00").toInstant();

    @Test
    void isSixtyFourLowercaseHexCharacters() {
        assertThat(RequestFingerprint.of(1, 2, 3, 4, TEN_SAIGON)).matches("[0-9a-f]{64}");
    }

    @Test
    void sameInstantInAnotherOffsetGivesTheSameFingerprint() {
        Instant sameInUtc = OffsetDateTime.parse("2026-11-03T03:00:00Z").toInstant();
        Instant sameWithFraction = OffsetDateTime.parse("2026-11-03T10:00:00.000+07:00").toInstant();

        assertThat(RequestFingerprint.of(1, 2, 3, 4, sameInUtc)).isEqualTo(RequestFingerprint.of(1, 2, 3, 4, TEN_SAIGON));
        assertThat(RequestFingerprint.of(1, 2, 3, 4, sameWithFraction))
                .isEqualTo(RequestFingerprint.of(1, 2, 3, 4, TEN_SAIGON));
    }

    @Test
    void everyParameterChangesTheFingerprint() {
        String original = RequestFingerprint.of(1, 2, 3, 4, TEN_SAIGON);

        assertThat(RequestFingerprint.of(9, 2, 3, 4, TEN_SAIGON)).isNotEqualTo(original);
        assertThat(RequestFingerprint.of(1, 9, 3, 4, TEN_SAIGON)).isNotEqualTo(original);
        assertThat(RequestFingerprint.of(1, 2, 9, 4, TEN_SAIGON)).isNotEqualTo(original);
        assertThat(RequestFingerprint.of(1, 2, 3, 9, TEN_SAIGON)).isNotEqualTo(original);
        assertThat(RequestFingerprint.of(1, 2, 3, 4, TEN_SAIGON.plusSeconds(1800))).isNotEqualTo(original);
    }

    @Test
    void idsAreSeparatedSoTheyCannotRunTogether() {
        assertThat(RequestFingerprint.of(1, 23, 3, 4, TEN_SAIGON)).isNotEqualTo(RequestFingerprint.of(12, 3, 3, 4, TEN_SAIGON));
    }
}
