package com.keyloop.scheduler.web.error;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import com.keyloop.scheduler.web.filter.RequestIdFilter;

final class Problems {

    private static final Logger log = LoggerFactory.getLogger(Problems.class);

    private static final URI BLANK_TYPE = URI.create("about:blank");

    record InputError(String field, String message) {
    }

    private Problems() {
    }

    static ProblemDetail create(HttpStatusCode status, ErrorCode code, String detail,
            @Nullable List<InputError> errors, HttpServletRequest request, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(BLANK_TYPE);
        HttpStatus known = HttpStatus.resolve(status.value());
        problem.setTitle(known != null ? known.getReasonPhrase() : null);
        problem.setInstance(URI.create(path));
        problem.setProperty("code", code.name());
        problem.setProperty("requestId", RequestIdFilter.of(request));
        if (errors != null) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    static void log(ProblemDetail problem, @Nullable Throwable cause) {
        if (problem.getStatus() >= 500) {
            log.error("{} {}: {}", problem.getStatus(), problem.getProperties().get("code"), problem.getDetail(), cause);
        }
        else {
            log.warn("{} {}: {}", problem.getStatus(), problem.getProperties().get("code"), problem.getDetail());
        }
    }
}
