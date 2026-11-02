package com.keyloop.scheduler.appointment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * The SHA-256 of a booking's parameters, stored with the appointment so that a repeated
 * {@code Idempotency-Key} can be checked against the original request. Built from normalised values,
 * not from the JSON, so offset notation, field order and whitespace do not matter. The {@code v1|}
 * prefix keeps a future change of the definition from silently matching old rows.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(long dealershipId, long customerId, long vehicleId, long serviceTypeId, Instant start) {
        String canonical = "v1|%d|%d|%d|%d|%s".formatted(dealershipId, customerId, vehicleId, serviceTypeId, start);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every Java platform provides SHA-256", ex);
        }
    }
}
