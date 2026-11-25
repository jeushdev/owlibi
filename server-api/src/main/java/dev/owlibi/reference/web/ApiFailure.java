package dev.owlibi.reference.web;

import java.util.Map;

/**
 * An expected failure that becomes an HTTP error response, such as a validation error or a missing project.
 *
 * <p>Only fixed, safe messages cross the API boundary. Never include exception diagnostics.
 */
public final class ApiFailure extends RuntimeException {
    private final int status;
    private final ApiError error;
    private final Integer retryAfter;

    /** A failure with no field errors and no retry delay. */
    public ApiFailure(int status, String code, String message) {
        this(status, code, message, Map.of(), null);
    }

    /** A failure with field errors and an optional {@code Retry-After} delay in seconds. */
    public ApiFailure(int status, String code, String message, Map<String, String> fields, Integer retryAfter) {
        super(code);
        this.status = status;
        this.error = new ApiError(code, message, fields);
        this.retryAfter = retryAfter;
    }

    /** The HTTP status code to send. */
    public int status() {
        return status;
    }

    /** The JSON body to send. */
    public ApiError error() {
        return error;
    }

    /** Seconds the client should wait before retrying, or {@code null}. */
    public Integer retryAfter() {
        return retryAfter;
    }

    /** The request could not be read at all (bad JSON, unknown fields, wrong types). */
    public static ApiFailure malformed() {
        return new ApiFailure(400, "MALFORMED_REQUEST", "Request is malformed");
    }

    /** The request was readable but one field holds an invalid value. */
    public static ApiFailure validation(String field) {
        return new ApiFailure(422, "VALIDATION_FAILED", "Check the supplied fields", Map.of(field, "Value is invalid"), null);
    }
}
