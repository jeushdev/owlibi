package dev.owlibi.reference.web;
import java.util.Map;
import java.util.LinkedHashSet;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiFailure.class) public ResponseEntity<ApiError> domain(ApiFailure failure) {
        var response = ResponseEntity.status(failure.status()).contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store");
        if (failure.retryAfter()!=null) response.header("Retry-After",failure.retryAfter().toString());
        return response.body(failure.error());
    }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> malformed(Exception failure) { return domain(ApiFailure.malformed()); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(org.springframework.web.HttpRequestMethodNotSupportedException failure,HttpServletRequest request) {
        var allowed=new LinkedHashSet<HttpMethod>();
        if(failure.getSupportedHttpMethods()!=null) allowed.addAll(failure.getSupportedHttpMethods());
        if(allowed.contains(HttpMethod.GET)) allowed.add(HttpMethod.HEAD);
        if(!allowed.isEmpty()) allowed.add(HttpMethod.OPTIONS);
        // This route deliberately refuses Spring MVC's implicit HEAD handling.
        if(request.getServletPath().matches("/api/v1/projects/[^/]+/professor-report")) allowed.remove(HttpMethod.HEAD);
        return ResponseEntity.status(405).contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store")
            .allow(allowed.toArray(HttpMethod[]::new)).body(new ApiError("MALFORMED_REQUEST","Method unavailable",Map.of()));
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> representation(Exception failure) {
        // Explicit JSON avoids a second negotiation failure and protected /error dispatch.
        return ResponseEntity.status(406).contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store")
            .body(new ApiError("NOT_ACCEPTABLE","Requested response format is unavailable",Map.of()));
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> missing(Exception failure) {
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store")
            .body(new ApiError("NOT_FOUND","Resource unavailable",Map.of()));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<ApiError> unavailable(Exception failure) {
        return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store")
            .body(new ApiError("SERVICE_UNAVAILABLE", "Service temporarily unavailable", Map.of()));
    }
}
