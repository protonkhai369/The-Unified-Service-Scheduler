package com.keyloop.scheduler.web.filter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    public static final String MDC_KEY = "requestId";

    static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Logger accessLog = LoggerFactory.getLogger("com.keyloop.scheduler.web.AccessLog");

    public static @Nullable String of(HttpServletRequest request) {
        return (String) request.getAttribute(ATTRIBUTE);
    }

    static String resolve(@Nullable String supplied) {
        return supplied != null && SAFE_ID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = of(request);
        if (requestId == null) {
            requestId = resolve(request.getHeader(HEADER));
            request.setAttribute(ATTRIBUTE, requestId);
        }
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        long start = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        }
        finally {
            if (isLoggedAccess(request)) {
                accessLog.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                        failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus(),
                        (System.nanoTime() - start) / 1_000_000);
            }
            MDC.remove(MDC_KEY);
        }
    }

    private static boolean isLoggedAccess(HttpServletRequest request) {
        return request.getDispatcherType() == DispatcherType.REQUEST && request.getRequestURI().startsWith("/api/");
    }
}
