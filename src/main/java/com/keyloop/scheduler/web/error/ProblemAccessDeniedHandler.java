package com.keyloop.scheduler.web.error;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers an authenticated caller whose role a URL rule does not allow (the actuator) with
 * {@code 403}; {@link ProblemErrorController} renders the body ({@code FORBIDDEN}) on the error
 * dispatch. Replaces the resource server's default handler, which sends no body. Rules that need the
 * request body or the appointment are checked by the services instead, which throw
 * {@code ApiException(FORBIDDEN)}.
 */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
