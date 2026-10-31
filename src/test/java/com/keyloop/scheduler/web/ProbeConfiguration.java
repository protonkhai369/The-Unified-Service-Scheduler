package com.keyloop.scheduler.web;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/**
 * Test-only endpoints and a failing filter that trigger the failures no real endpoint can trigger
 * yet (bodies, query parameters, headers, unexpected exceptions, a failure outside Spring MVC).
 * Imported by {@code DatabaseIntegrationTest}, so every integration test keeps sharing one context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ProbeConfiguration {

    public static final String PROBE_PATH = "/api/test-probe";

    public static final String FILTER_FAILURE_PATH = PROBE_PATH + "/filter-failure";

    /** Registered as a member class of this configuration. */
    @RestController
    @RequestMapping(PROBE_PATH)
    static class ProbeController {

        record ProbeBody(@NotNull @Positive Long dealershipId, @NotNull Long vehicleId) {
        }

        @GetMapping("/ok")
        Map<String, String> ok() {
            return Map.of("status", "ok");
        }

        @PostMapping("/body")
        ProbeBody body(@Valid @RequestBody ProbeBody body) {
            return body;
        }

        @GetMapping("/query")
        Map<String, String> query(@RequestParam String date) {
            return Map.of("date", date);
        }

        @GetMapping("/header")
        Map<String, String> header(@RequestHeader("X-Probe") String probe) {
            return Map.of("probe", probe);
        }

        @GetMapping("/failure")
        Map<String, String> failure() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/gone")
        Map<String, String> gone() {
            throw new ResponseStatusException(HttpStatus.GONE);
        }
    }

    @Bean
    FilterRegistrationBean<Filter> failingProbeFilter() {
        Filter filter = new OncePerRequestFilter() {

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                if (FILTER_FAILURE_PATH.equals(request.getRequestURI())) {
                    throw new IllegalStateException("secret filter detail");
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(0);
        return registration;
    }
}
