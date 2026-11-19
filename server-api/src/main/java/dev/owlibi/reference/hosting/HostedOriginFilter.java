package dev.owlibi.reference.hosting;

import dev.owlibi.reference.security.SessionBoundaryFilter;
import dev.owlibi.reference.web.ApiFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** Origin supplements CSRF/authentication; it never authenticates a proxy or direct client. */
public final class HostedOriginFilter extends OncePerRequestFilter {
    private final String origin;
    public HostedOriginFilter(String origin) { this.origin = HostedPolicy.origin(origin); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        boolean api = request.getRequestURI().startsWith("/api/v1/");
        boolean unsafe = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        var origins = Collections.list(request.getHeaders("Origin"));
        if (api && ((unsafe && origins.isEmpty()) || (!origins.isEmpty() && (origins.size() != 1 || !origin.equals(origins.getFirst()))))) {
            SessionBoundaryFilter.write(response, new ApiFailure(403, "ORIGIN_INVALID", "Request origin is unavailable"));
            return;
        }
        chain.doFilter(request, response);
    }
}
