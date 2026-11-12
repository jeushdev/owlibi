package dev.owlibi.reference.web;

import java.util.Map;

/** Only fixed, safe messages cross the API boundary. Never include exception diagnostics. */
public final class ApiFailure extends RuntimeException {
    private final int status;
    private final ApiError error;
    private final Integer retryAfter;
    public ApiFailure(int status, String code, String message) { this(status, code, message, Map.of(), null); }
    public ApiFailure(int status, String code, String message, Map<String,String> fields, Integer retryAfter) {
        super(code); this.status = status; this.error = new ApiError(code, message, fields); this.retryAfter = retryAfter;
    }
    public int status() { return status; }
    public ApiError error() { return error; }
    public Integer retryAfter() { return retryAfter; }
    public static ApiFailure malformed() { return new ApiFailure(400,"MALFORMED_REQUEST","Request is malformed"); }
    public static ApiFailure validation(String field) {
        return new ApiFailure(422,"VALIDATION_FAILED","Check the supplied fields",Map.of(field,"Value is invalid"),null);
    }
}
