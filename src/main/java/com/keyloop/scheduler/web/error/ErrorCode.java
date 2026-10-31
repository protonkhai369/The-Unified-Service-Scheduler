package com.keyloop.scheduler.web.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Every error code of the API: the constant's name is the {@code code} member of the problem
 * response, so renaming a constant is a breaking API change. Each code is also defined in a spec
 * under {@code openspec/} ({@code ErrorCodeTest} checks this). Detail templates hold only values the
 * client sent, such as ids, never internal state.
 */
public enum ErrorCode {

    // Request and framework errors
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "No endpoint matches this path."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "This method is not supported for this path."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This content type is not supported."),
    REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "The request was rejected."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred."),

    // Catalog
    DEALERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "Dealership %d does not exist.");

    private final HttpStatus status;

    private final String detailTemplate;

    ErrorCode(HttpStatus status, String detailTemplate) {
        this.status = status;
        this.detailTemplate = detailTemplate;
    }

    public HttpStatus status() {
        return this.status;
    }

    public String detail(Object... args) {
        return this.detailTemplate.formatted(args);
    }

    /**
     * The code for an error without a more specific one. With {@link #REQUEST_REJECTED} and
     * {@link #INTERNAL_ERROR} the response keeps the given status, not the constant's default.
     */
    public static ErrorCode forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? REQUEST_REJECTED : INTERNAL_ERROR;
        };
    }
}
