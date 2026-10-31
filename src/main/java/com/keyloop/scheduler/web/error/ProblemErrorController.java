package com.keyloop.scheduler.web.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * Answers the servlet container's error dispatch (an exception thrown in a filter, a request the
 * container rejected) with the same problem format as {@link GlobalExceptionHandler}, replacing
 * Spring Boot's own error JSON. The exception message is never exposed. Hidden from the OpenAPI
 * document, like Boot's error controller it replaces: clients never call /error themselves.
 */
@Hidden
@RestController
public class ProblemErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatusCode status = HttpStatusCode.valueOf(statusAttribute instanceof Integer value ? value : 500);
        Object uriAttribute = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = uriAttribute instanceof String uri ? uri : request.getRequestURI();

        ErrorCode code = ErrorCode.forStatus(status);
        ProblemDetail problem = Problems.create(status, code, code.detail(), null, request, path);
        Problems.log(problem, (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
