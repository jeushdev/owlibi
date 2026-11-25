package dev.owlibi.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every exception a controller throws into a JSON {@link ApiError} response with a safe message. */
@RestControllerAdvice
public class ApiExceptionHandler {
    /** Expected failures carry their own status, code and message. */
    @ExceptionHandler(ApiFailure.class)
    public ResponseEntity<ApiError> domain(ApiFailure failure) {
        var response = ResponseEntity.status(failure.status())
            .contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store");
        if (failure.retryAfter() != null) {
            response.header("Retry-After", failure.retryAfter().toString());
        }
        return response.body(failure.error());
    }

    /** A path or query value Spring could not convert, such as a project id that is not a UUID. */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> malformed(Exception failure) {
        return domain(ApiFailure.malformed());
    }

    /** A known path called with the wrong HTTP method; the {@code Allow} header lists the right ones. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException failure, HttpServletRequest request) {
        var allowed = new LinkedHashSet<HttpMethod>();
        if (failure.getSupportedHttpMethods() != null) {
            allowed.addAll(failure.getSupportedHttpMethods());
        }
        if (allowed.contains(HttpMethod.GET)) {
            allowed.add(HttpMethod.HEAD);
        }
        if (!allowed.isEmpty()) {
            allowed.add(HttpMethod.OPTIONS);
        }
        // This route deliberately refuses Spring MVC's implicit HEAD handling.
        if (request.getServletPath().matches("/api/v1/projects/[^/]+/professor-report")) {
            allowed.remove(HttpMethod.HEAD);
        }
        return ResponseEntity.status(405)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .allow(allowed.toArray(HttpMethod[]::new))
            .body(new ApiError("MALFORMED_REQUEST", "Method unavailable", Map.of()));
    }

    /** The client asked for a response format other than JSON. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> representation(Exception failure) {
        // Explicit JSON avoids a second negotiation failure and protected /error dispatch.
        return ResponseEntity.status(406)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .body(new ApiError("NOT_ACCEPTABLE", "Requested response format is unavailable", Map.of()));
    }

    /** No route or static file matches the path. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> missing(Exception failure) {
        return ResponseEntity.status(404)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .body(new ApiError("NOT_FOUND", "Resource unavailable", Map.of()));
    }

    /** Anything unexpected becomes a generic 503 so no internal detail leaks. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unavailable(Exception failure) {
        return ResponseEntity.status(503)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .body(new ApiError("SERVICE_UNAVAILABLE", "Service temporarily unavailable", Map.of()));
    }
}
