package com.keyloop.scheduler.web.error;

/**
 * A failure the client caused or must know about, answered with the code's status and a problem
 * response. Thrown by application services, never by the pure scheduling domain.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    /** @param detailArgs the values for the code's detail template, e.g. the id that was not found */
    public ApiException(ErrorCode code, Object... detailArgs) {
        super(code.detail(detailArgs));
        this.code = code;
    }

    public ErrorCode code() {
        return this.code;
    }
}
