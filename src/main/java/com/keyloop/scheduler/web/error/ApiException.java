package com.keyloop.scheduler.web.error;

public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, Object... detailArgs) {
        super(code.detail(detailArgs));
        this.code = code;
    }

    public ErrorCode code() {
        return this.code;
    }
}
