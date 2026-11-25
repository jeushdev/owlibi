package dev.owlibi.security;

import dev.owlibi.web.ApiFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after the CSRF check. A request that changes data must send the session epoch it was given at
 * sign-in, so a tab left open from an earlier sign-in cannot act on the new one. The expiry is then
 * checked again, atomically. An expired or replaced session is never revived or replayed.
 */
public final class SessionEpochFilter extends OncePerRequestFilter {
    private final Clock clock;

    public SessionEpochFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var state = (SessionState) request.getAttribute(SessionState.ATTRIBUTE);
        if (state != null && state.account() != null && SessionBoundaryFilter.unsafe(request)) {
            String epoch = request.getHeader("X-Session-Epoch");
            if (epoch == null || epoch.isBlank()) {
                throw new ApiFailure(400, "SESSION_CONTEXT_REQUIRED", "Current session context is required");
            }
            if (!state.epoch().toString().equals(epoch)) {
                throw new ApiFailure(409, "SESSION_CHANGED", "Session has changed; revalidate sign in context");
            }
            if (state.expired(clock.instant())) {
                state.revoke();
                request.getSession().invalidate();
                SecurityContextHolder.clearContext();
                throw new ApiFailure(401, "UNAUTHENTICATED", "Sign in is required");
            }
        }
        chain.doFilter(request, response);
    }
}
