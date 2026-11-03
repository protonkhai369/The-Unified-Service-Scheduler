package com.keyloop.scheduler.web.error;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers a request without a valid access token: {@code 401} with the RFC 6750 challenge
 * {@code WWW-Authenticate: Bearer}. The body is rendered by {@link ProblemErrorController} on the
 * container's error dispatch ({@code UNAUTHENTICATED}), like every failure outside Spring MVC. The
 * reason the token was rejected is not sent: it can describe the token's claims.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
