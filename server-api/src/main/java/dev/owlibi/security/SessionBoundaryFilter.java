package dev.owlibi.security;

import dev.owlibi.web.ApiFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The first security check on every API request: it finds the session, ends it when it has expired or
 * been revoked, and signs the request in from it. It runs before the CSRF check.
 *
 * <p>Each request holds the session's {@link SessionState} monitor until it finishes, so two requests of
 * one session never change data at the same time. Only the long professor-report download releases it
 * early.
 */
public final class SessionBoundaryFilter extends OncePerRequestFilter {
    private static final String HEALTH_PATH = "/api/v1/health";
    private static final String SESSIONS_PATH = "/api/v1/auth/sessions";
    private static final String ACCOUNTS_PATH = "/api/v1/auth/accounts";
    private static final String REPORT_PATH = "/api/v1/projects/[^/]+/professor-report";
    private static final List<String> SAFE_METHODS = List.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final Clock clock;

    public SessionBoundaryFilter(Clock clock) {
        this.clock = clock;
    }

    /** Whether the request may change data (anything but GET, HEAD, OPTIONS or TRACE). */
    static boolean unsafe(HttpServletRequest request) {
        return !SAFE_METHODS.contains(request.getMethod());
    }

    /** The few routes that work without signing in: health, the CSRF token, registration and sign-in. */
    private static boolean publicApi(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();
        boolean publicRead = method.equals("GET") && (path.equals(HEALTH_PATH) || path.equals(SESSIONS_PATH));
        boolean publicWrite = method.equals("POST") && (path.equals(ACCOUNTS_PATH) || path.equals(SESSIONS_PATH));
        return publicRead || publicWrite;
    }

    private static boolean healthCheck(HttpServletRequest request) {
        return request.getMethod().equals("GET") && request.getServletPath().equals(HEALTH_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getServletPath().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        boolean publicRoute = publicApi(request);
        HttpSession session = request.getSession(false);
        if (session == null && publicRoute && !request.getServletPath().equals(HEALTH_PATH)) {
            session = request.getSession(true);
        }
        if (session == null) {
            if (publicRoute) {
                chain.doFilter(request, response);
            } else {
                write(response, unauthenticated());
            }
            return;
        }

        SessionState state;
        // Request-specific session facades may differ; protect attribute installation only.
        try {
            synchronized (request.getServletContext()) {
                state = (SessionState) session.getAttribute(SessionState.ATTRIBUTE);
                if (state == null) {
                    state = new SessionState();
                    session.setAttribute(SessionState.ATTRIBUTE, state);
                }
            }
        } catch (IllegalStateException ended) {
            SecurityContextHolder.clearContext();
            write(response, unauthenticated());
            return;
        }

        boolean reportRead = request.getMethod().equals("GET") && request.getServletPath().matches(REPORT_PATH);
        synchronized (state) {
            if (state.revoked() || state.expired(clock.instant())) {
                state.revoke();
                try {
                    session.invalidate();
                } catch (IllegalStateException alreadyEnded) {
                    // Ended by another request.
                }
                SecurityContextHolder.clearContext();
                if (healthCheck(request)) {
                    chain.doFilter(request, response);
                } else {
                    write(response, unauthenticated());
                }
                return;
            }
            // Reload under the monitor; a login may have replaced a context loaded earlier.
            var context = SecurityContextHolder.createEmptyContext();
            if (state.account() != null) {
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(state.account(), null, List.of()));
                response.setHeader("X-Session-Epoch", state.epoch().toString());
            }
            SecurityContextHolder.setContext(context);
            if (!publicRoute && state.account() == null) {
                write(response, unauthenticated());
                return;
            }
            request.setAttribute(SessionState.ATTRIBUTE, state);
            if (!reportRead) {
                continueChain(request, response, chain);
                return;
            }
            request.setAttribute(SessionState.ATTRIBUTE + ".epoch", state.epoch());
        }
        // Only the long report GET releases the monitor. It rechecks this captured identity/epoch before delivery.
        continueChain(request, response, chain);
    }

    /** Runs the rest of the chain, writing any {@link ApiFailure} thrown by a later filter as JSON. */
    private static void continueChain(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ApiFailure failure) {
            write(response, failure);
        }
    }

    private static ApiFailure unauthenticated() {
        return new ApiFailure(401, "UNAUTHENTICATED", "Sign in is required");
    }

    /** Writes {@code failure} as a JSON error response directly, for code that runs outside Spring MVC. */
    public static void write(HttpServletResponse response, ApiFailure failure) throws IOException {
        response.setStatus(failure.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (failure.retryAfter() != null) {
            response.setHeader("Retry-After", failure.retryAfter().toString());
        }
        response.getWriter().write(JsonMapper.shared().writeValueAsString(failure.error()));
    }
}
