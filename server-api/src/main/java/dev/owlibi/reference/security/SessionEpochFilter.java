package dev.owlibi.reference.security;
import dev.owlibi.reference.web.ApiFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import org.springframework.web.filter.OncePerRequestFilter;

/** CSRF passed; epoch then atomic expiry recheck. Never revive or replay an intent. */
public final class SessionEpochFilter extends OncePerRequestFilter {
    private final Clock clock;
    public SessionEpochFilter(Clock clock) { this.clock=clock; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var state=(SessionState)request.getAttribute(SessionState.ATTRIBUTE);
        if (state!=null && state.account()!=null && SessionBoundaryFilter.unsafe(request)) {
            String epoch=request.getHeader("X-Session-Epoch");
            if (epoch==null || epoch.isBlank()) throw new ApiFailure(400,"SESSION_CONTEXT_REQUIRED","Current session context is required");
            if (!state.epoch().toString().equals(epoch)) throw new ApiFailure(409,"SESSION_CHANGED","Session has changed; revalidate sign in context");
            if (state.expired(clock.instant())) {
                state.revoke(); request.getSession().invalidate(); org.springframework.security.core.context.SecurityContextHolder.clearContext();
                throw new ApiFailure(401,"UNAUTHENTICATED","Sign in is required");
            }
        }
        chain.doFilter(request,response);
    }
}
