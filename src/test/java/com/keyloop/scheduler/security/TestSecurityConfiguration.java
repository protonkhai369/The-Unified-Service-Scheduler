package com.keyloop.scheduler.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

/**
 * Lets tests sign their own access tokens without Supabase. The application keeps the decoder Spring
 * Boot builds from {@code application.yml} (ES256 only, issuer, audience, expiry); only the download
 * of the signing keys is replaced, by the public key of {@link TestTokens}. Imported by
 * {@code DatabaseIntegrationTest}, which sets {@code SUPABASE_URL} to {@link #SUPABASE_URL}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    public static final String SUPABASE_URL = "https://test.supabase.co";

    private static final URI JWKS_URI = URI.create(SUPABASE_URL + "/auth/v1/.well-known/jwks.json");

    @Bean
    TestTokens testTokens() {
        return new TestTokens(SUPABASE_URL + "/auth/v1");
    }

    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer testSigningKeys(TestTokens tokens) {
        byte[] jwks = tokens.publicKeys().getBytes(UTF_8);
        RestTemplate keyDownloads = new RestTemplate((uri, method) -> {
            Assert.state(JWKS_URI.equals(uri), () -> "Unexpected signing key download from " + uri);
            MockClientHttpResponse response = new MockClientHttpResponse(jwks, HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            request.setResponse(response);
            return request;
        });
        return builder -> builder.restOperations(keyDownloads);
    }
}
