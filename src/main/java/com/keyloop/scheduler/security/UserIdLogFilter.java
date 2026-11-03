package com.keyloop.scheduler.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the caller's Supabase user id into the MDC as {@value #MDC_KEY}, so every log line of an
 * authenticated request carries it next to the request id. Never the email or a name. Runs inside the
 * security chain, after the token is verified; also on the error dispatch, so a 403 problem line names
 * the caller. Not a bean, so Spring Boot does not register it a second time as a servlet filter.
 */
class UserIdLogFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "userId";

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put(MDC_KEY, user.userId().toString());
        try {
            chain.doFilter(request, response);
        }
        finally {
            MDC.remove(MDC_KEY);
        }
    }
}
