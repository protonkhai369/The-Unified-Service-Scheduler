package com.keyloop.scheduler.web.error;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.keyloop.scheduler.web.error.Problems.InputError;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Turns every exception raised while Spring MVC handles a request into the API's problem format.
 * The base class already maps each Spring MVC exception to its status and headers (e.g. {@code Allow}
 * on a 405); all of them pass through {@link #handleExceptionInternal}, where the code, the request
 * id and the input errors are added. Failures outside Spring MVC are handled by
 * {@link ProblemErrorController}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        return respond(ex, ex.code().status(), ex.code(), ex.getMessage(), null, new HttpHeaders(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        return respond(ex, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.detail(), null, new HttpHeaders(), request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.value() == 400 && isInputError(ex)) {
            return respond(ex, statusCode, ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.detail(),
                    inputErrors(ex), headers, request);
        }
        ErrorCode code = ErrorCode.forStatus(statusCode);
        return respond(ex, statusCode, code, detail(ex, code), null, headers, request);
    }

    private @Nullable ResponseEntity<Object> respond(Exception ex, HttpStatusCode status, ErrorCode code,
            String detail, @Nullable List<InputError> errors, HttpHeaders headers, WebRequest request) {
        HttpServletRequest servletRequest = ((NativeWebRequest) request).getNativeRequest(HttpServletRequest.class);
        ProblemDetail problem = Problems.create(status, code, detail, errors, servletRequest,
                servletRequest.getRequestURI());
        Problems.log(problem, ex);
        return super.handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static boolean isInputError(Exception ex) {
        return ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException
                || ex instanceof TypeMismatchException || ex instanceof MissingServletRequestParameterException
                || ex instanceof ServletRequestBindingException || ex instanceof HttpMessageNotReadableException;
    }

    /**
     * Spring's own detail where it helps the client (e.g. "Method 'DELETE' is not supported."), but
     * not for a 404, whose Spring detail ("No static resource ...") describes server internals, nor
     * for server errors.
     */
    private static String detail(Exception ex, ErrorCode code) {
        if (code != ErrorCode.NOT_FOUND && code != ErrorCode.INTERNAL_ERROR && ex instanceof ErrorResponse response
                && StringUtils.hasText(response.getBody().getDetail())) {
            return response.getBody().getDetail();
        }
        return code.detail();
    }

    private static List<InputError> inputErrors(Exception ex) {
        List<InputError> errors = new ArrayList<>();
        switch (ex) {
            case MethodArgumentNotValidException invalid -> {
                invalid.getBindingResult().getFieldErrors().forEach(error ->
                        errors.add(new InputError(error.getField(), String.valueOf(error.getDefaultMessage()))));
                invalid.getBindingResult().getGlobalErrors().forEach(error ->
                        errors.add(new InputError(error.getObjectName(), String.valueOf(error.getDefaultMessage()))));
            }
            case HandlerMethodValidationException invalid -> {
                for (ParameterValidationResult result : invalid.getParameterValidationResults()) {
                    String name = parameterName(result.getMethodParameter());
                    result.getResolvableErrors().forEach(error ->
                            errors.add(new InputError(name, String.valueOf(error.getDefaultMessage()))));
                }
            }
            case MethodArgumentTypeMismatchException mismatch ->
                    errors.add(new InputError(mismatch.getName(), mustBeOfType(mismatch.getRequiredType())));
            case MissingServletRequestParameterException missing ->
                    errors.add(new InputError(missing.getParameterName(), "is required"));
            case MissingRequestHeaderException missing ->
                    errors.add(new InputError(missing.getHeaderName(), "is required"));
            case HttpMessageNotReadableException unreadable -> {
                // Jackson names the field only when the JSON was well-formed but a value did not fit.
                if (unreadable.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
                    errors.add(new InputError(jsonPath(mismatch.getPath()), mustBeOfType(mismatch.getTargetType())));
                }
            }
            default -> {
            }
        }
        return errors;
    }

    /** The name the client used: the annotation's name for parameters and headers, else the Java name. */
    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        PathVariable variable = parameter.getParameterAnnotation(PathVariable.class);
        String declared = header != null ? firstText(header.name(), header.value())
                : param != null ? firstText(param.name(), param.value())
                : variable != null ? firstText(variable.name(), variable.value()) : "";
        return StringUtils.hasText(declared) ? declared : String.valueOf(parameter.getParameterName());
    }

    private static String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    /** {@code items[0].dealershipId} for Jackson's path [items, 0, dealershipId]. */
    private static String jsonPath(List<JacksonException.Reference> path) {
        StringBuilder result = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            if (reference.getPropertyName() != null) {
                result.append(result.isEmpty() ? "" : ".").append(reference.getPropertyName());
            }
            else if (reference.getIndex() >= 0) {
                result.append('[').append(reference.getIndex()).append(']');
            }
        }
        return result.toString();
    }

    private static String mustBeOfType(@Nullable Class<?> type) {
        return type != null ? "must be a value of type " + type.getSimpleName() : "has an invalid value";
    }
}
