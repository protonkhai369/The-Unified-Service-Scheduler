package com.keyloop.scheduler.web.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "No endpoint matches this path."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "This method is not supported for this path."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This content type is not supported."),
    REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "The request was rejected."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred."),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A valid bearer access token is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "The caller is not allowed to perform this request."),

    DEALERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "Dealership %d does not exist."),
    SERVICE_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "Service type %d does not exist."),

    CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND, "Customer %d does not exist."),
    VEHICLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Vehicle %d does not exist."),

    APPOINTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Appointment %s does not exist."),
    VEHICLE_NOT_OWNED_BY_CUSTOMER(HttpStatus.UNPROCESSABLE_CONTENT, "Vehicle %d does not belong to customer %d."),
    START_TIME_IN_PAST(HttpStatus.UNPROCESSABLE_CONTENT, "The start time must be in the future."),
    OUTSIDE_OPENING_HOURS(HttpStatus.UNPROCESSABLE_CONTENT,
            "The service must start and end within the dealership's opening hours."),
    START_TIME_NOT_ON_GRID(HttpStatus.UNPROCESSABLE_CONTENT,
            "The start time must be a whole number of slot steps after the dealership's opening time."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT,
            "This Idempotency-Key was already used for a different booking."),
    NO_AVAILABILITY(HttpStatus.CONFLICT,
            "No service bay and qualified technician are both free for the whole requested period."),
    SLOT_CONTENTION(HttpStatus.CONFLICT, "Too many bookings are competing for this dealership and day; retry shortly."),
    VEHICLE_ALREADY_BOOKED(HttpStatus.CONFLICT, "Vehicle %d already has an appointment overlapping this period."),
    APPOINTMENT_ALREADY_STARTED(HttpStatus.CONFLICT, "Appointment %s has already started and cannot be cancelled.");

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

    public static ErrorCode forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? REQUEST_REJECTED : INTERNAL_ERROR;
        };
    }
}
