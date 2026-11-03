package com.keyloop.scheduler.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Signs access tokens shaped like Supabase Auth's (ES256, {@code kid}, {@code aud: authenticated},
 * {@code app_metadata}) with a key pair generated for the test run. Every token is for the same user
 * ({@link #USER_ID}, {@link #EMAIL}); only its role and claims differ. Tokens are valid for an hour
 * of real time, because Spring checks expiry against the system clock, not the test clock.
 */
public final class TestTokens {

    public static final UUID USER_ID = UUID.fromString("5a1f0c3e-7d2b-4c9a-9e1f-2b3c4d5e6f70");

    public static final String EMAIL = "test.user@example.com";

    private static final String KEY_ID = "test-signing-key";

    private final ECKey signingKey = generateKey();

    /** Same key id as the published key, so only the signature check can reject it. */
    private final ECKey unpublishedKey = generateKey();

    private final String issuer;

    TestTokens(String issuer) {
        this.issuer = issuer;
    }

    public String admin() {
        return sign(claims(appMetadata("admin", null, null)));
    }

    public String customer(long customerId) {
        return sign(claims(appMetadata("customer", customerId, null)));
    }

    public String advisor(long dealershipId) {
        return sign(claims(appMetadata("advisor", null, dealershipId)));
    }

    public String withoutRole() {
        return sign(claims(appMetadata(null, null, null)));
    }

    public String advisorWithoutDealershipId() {
        return sign(claims(appMetadata("advisor", null, null)));
    }

    public String customerWithoutCustomerId() {
        return sign(claims(appMetadata("customer", null, null)));
    }

    /** No role where Supabase lets only administrators write, {@code admin} where users can write. */
    public String adminInUserMetadata() {
        return sign(claims(appMetadata(null, null, null)).claim("user_metadata", Map.of("scheduler_role", "admin")));
    }

    public String expired() {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        return sign(claims(appMetadata("admin", null, null))
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plus(Duration.ofHours(1)))));
    }

    public String fromAnotherProject() {
        return sign(claims(appMetadata("admin", null, null)).issuer("https://another-project.supabase.co/auth/v1"));
    }

    public String withWrongAudience() {
        return sign(claims(appMetadata("admin", null, null)).audience("anon"));
    }

    public String signedByAnUnpublishedKey() {
        return sign(claims(appMetadata("admin", null, null)).build(), this.unpublishedKey);
    }

    /** The JWKS document the application downloads instead of Supabase's. */
    String publicKeys() {
        return new JWKSet(this.signingKey.toPublicJWK()).toString();
    }

    private JWTClaimsSet.Builder claims(Map<String, Object> appMetadata) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(this.issuer)
                .audience("authenticated")
                .subject(USER_ID.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .claim("email", EMAIL)
                .claim("role", "authenticated")
                .claim("app_metadata", appMetadata)
                .claim("user_metadata", Map.of());
    }

    private static Map<String, Object> appMetadata(@Nullable String role, @Nullable Long customerId,
            @Nullable Long dealershipId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", "email");
        metadata.put("providers", List.of("email"));
        putIfPresent(metadata, "scheduler_role", role);
        putIfPresent(metadata, "customer_id", customerId);
        putIfPresent(metadata, "dealership_id", dealershipId);
        return metadata;
    }

    private static void putIfPresent(Map<String, Object> map, String name, @Nullable Object value) {
        if (value != null) {
            map.put(name, value);
        }
    }

    private String sign(JWTClaimsSet.Builder claims) {
        return sign(claims.build(), this.signingKey);
    }

    private static String sign(JWTClaimsSet claims, ECKey key) {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(JOSEObjectType.JWT)
                .build();
        SignedJWT token = new SignedJWT(header, claims);
        try {
            token.sign(new ECDSASigner(key));
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
        return token.serialize();
    }

    private static ECKey generateKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).algorithm(JWSAlgorithm.ES256)
                    .keyUse(KeyUse.SIGNATURE).generate();
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
